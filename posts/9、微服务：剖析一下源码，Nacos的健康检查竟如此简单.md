# 微服务：剖析一下源码，Nacos的健康检查竟如此简单

## 前言

前面我们多次提到Nacos的健康检查，比如《[微服务之：服务挂的太干脆，Nacos还没反应过来，怎么办？](https://mp.weixin.qq.com/s/fDtcQD1EL-NgVV1BMiPx4g)》一文中还对健康检查进行了自定义调优。那么，Nacos的健康检查和心跳机制到底是如何实现的呢？在项目实践中是否又可以参考Nacos的健康检查机制，运用于其他地方呢？

这篇文章，就带大家来揭开Nacos健康检查机制的面纱。

## Nacos的健康检查

Nacos中临时实例基于心跳上报方式维持活性，基本的健康检查流程基本如下：Nacos客户端会维护一个定时任务，每隔5秒发送一次心跳请求，以确保自己处于活跃状态。Nacos服务端在15秒内如果没收到客户端的心跳请求，会将该实例设置为不健康，在30秒内没收到心跳，会将这个临时实例摘除。

原理很简单，关于代码层的实现，下面来就逐步来进行解析。

## 客户端的心跳

实例基于心跳上报的形式来维持活性，当然就离不开心跳功能的实现了。这里以客户端心跳实现为基准来进行分析。

Spring Cloud提供了一个标准接口ServiceRegistry，Nacos对应的实现类为NacosServiceRegistry。Spring Cloud项目启动时会实例化NacosServiceRegistry，并调用它的register方法来进行实例的注册。

```
@Override
public void register(Registration registration) { 
   // ...
   NamingService namingService = namingService();
   String serviceId = registration.getServiceId();
   String group = nacosDiscoveryProperties.getGroup();

   Instance instance = getNacosInstanceFromRegistration(registration);

   try {
      namingService.registerInstance(serviceId, group, instance);
      log.info("nacos registry, {} {} {}:{} register finished", group, serviceId,
            instance.getIp(), instance.getPort());
   }catch (Exception e) {
      // ...
   }
}
```
在该方法中有两处需要注意，第一处是构建Instance的getNacosInstanceFromRegistration方法，该方法内会设置Instance的元数据（metadata），通过源元数据可以配置服务器端健康检查的参数。比如，在Spring Cloud中配置的如下参数，都可以通过元数据项在服务注册时传递给Nacos的服务端。

```
spring:
  application:
    name: user-service-provider
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        heart-beat-interval: 5000
        heart-beat-timeout: 15000
       ip-delete-timeout: 30000
```
其中的heart-beat-interval、heart-beat-timeout、ip-delete-timeout这些健康检查的参数，都是基于元数据上报上去的。

register方法的第二处就是调用NamingService#registerInstance来进行实例的注册。NamingService是由Nacos的客户端提供，也就是说Nacos客户端的心跳本身是由Nacos生态提供的。

在registerInstance方法中最终会调用到下面的方法：

```
@Override
public void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException {
    NamingUtils.checkInstanceIsLegal(instance);
    String groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
    if (instance.isEphemeral()) {
        BeatInfo beatInfo = beatReactor.buildBeatInfo(groupedServiceName, instance);
        beatReactor.addBeatInfo(groupedServiceName, beatInfo);
    }
    serverProxy.registerService(groupedServiceName, groupName, instance);
}
```
其中BeatInfo#addBeatInfo便是进行心跳处理的入口。当然，前提条件是当前的实例需要是临时（瞬时）实例。

对应的方法实现如下：

```
public void addBeatInfo(String serviceName, BeatInfo beatInfo) {
    NAMING_LOGGER.info("[BEAT] adding beat: {} to beat map.", beatInfo);
    String key = buildKey(serviceName, beatInfo.getIp(), beatInfo.getPort());
    BeatInfo existBeat = null;
    //fix #1733
    if ((existBeat = dom2Beat.remove(key)) != null) {
        existBeat.setStopped(true);
    }
    dom2Beat.put(key, beatInfo);
    executorService.schedule(new BeatTask(beatInfo), beatInfo.getPeriod(), TimeUnit.MILLISECONDS);
    MetricsMonitor.getDom2BeatSizeMonitor().set(dom2Beat.size());
}
```
在倒数第二行可以看到，客户端是通过定时任务来处理心跳的，具体的心跳请求由BeatTask完成。定时任务的执行频次，封装在BeatInfo，回退往上看，会发现BeatInfo的Period来源于Instance#getInstanceHeartBeatInterval()。该方法具体实现如下：

```
public long getInstanceHeartBeatInterval() {
    return this.getMetaDataByKeyWithDefault("preserved.heart.beat.interval", Constants.DEFAULT_HEART_BEAT_INTERVAL);
}
```
可以看出定时任务的执行间隔就是配置的metadata中的数据preserved.heart.beat.interval，与上面提到配置heart-beat-interval本质是一回事，默认是5秒。

BeatTask类具体实现如下：

```
class BeatTask implements Runnable {
    
    BeatInfo beatInfo;
    
    public BeatTask(BeatInfo beatInfo) {
        this.beatInfo = beatInfo;
    }
    
    @Override
    public void run() {
        if (beatInfo.isStopped()) {
            return;
        }
        long nextTime = beatInfo.getPeriod();
        try {
            JsonNode result = serverProxy.sendBeat(beatInfo, BeatReactor.this.lightBeatEnabled);
            long interval = result.get("clientBeatInterval").asLong();
            boolean lightBeatEnabled = false;
            if (result.has(CommonParams.LIGHT_BEAT_ENABLED)) {
                lightBeatEnabled = result.get(CommonParams.LIGHT_BEAT_ENABLED).asBoolean();
            }
            BeatReactor.this.lightBeatEnabled = lightBeatEnabled;
            if (interval > 0) {
                nextTime = interval;
            }
            int code = NamingResponseCode.OK;
            if (result.has(CommonParams.CODE)) {
                code = result.get(CommonParams.CODE).asInt();
            }
            if (code == NamingResponseCode.RESOURCE_NOT_FOUND) {
                Instance instance = new Instance();
                instance.setPort(beatInfo.getPort());
                instance.setIp(beatInfo.getIp());
                instance.setWeight(beatInfo.getWeight());
                instance.setMetadata(beatInfo.getMetadata());
                instance.setClusterName(beatInfo.getCluster());
                instance.setServiceName(beatInfo.getServiceName());
                instance.setInstanceId(instance.getInstanceId());
                instance.setEphemeral(true);
                try {
                    serverProxy.registerService(beatInfo.getServiceName(),
                            NamingUtils.getGroupName(beatInfo.getServiceName()), instance);
                } catch (Exception ignore) {
                }
            }
        } catch (NacosException ex) {
            NAMING_LOGGER.error("[CLIENT-BEAT] failed to send beat: {}, code: {}, msg: {}",
                    JacksonUtils.toJson(beatInfo), ex.getErrCode(), ex.getErrMsg());
            
        }
        executorService.schedule(new BeatTask(beatInfo), nextTime, TimeUnit.MILLISECONDS);
    }
}
```
在run方法中通过NamingProxy#sendBeat完成了心跳请求的发送，而在run方法的最后，再次开启了一个定时任务，这样周期性的进行心跳请求。

NamingProxy#sendBeat方法实现如下：

```
public JsonNode sendBeat(BeatInfo beatInfo, boolean lightBeatEnabled) throws NacosException {
    
    if (NAMING_LOGGER.isDebugEnabled()) {
        NAMING_LOGGER.debug("[BEAT] {} sending beat to server: {}", namespaceId, beatInfo.toString());
    }
    Map<String, String> params = new HashMap<String, String>(8);
    Map<String, String> bodyMap = new HashMap<String, String>(2);
    if (!lightBeatEnabled) {
        bodyMap.put("beat", JacksonUtils.toJson(beatInfo));
    }
    params.put(CommonParams.NAMESPACE_ID, namespaceId);
    params.put(CommonParams.SERVICE_NAME, beatInfo.getServiceName());
    params.put(CommonParams.CLUSTER_NAME, beatInfo.getCluster());
    params.put("ip", beatInfo.getIp());
    params.put("port", String.valueOf(beatInfo.getPort()));
    String result = reqApi(UtilAndComs.nacosUrlBase + "/instance/beat", params, bodyMap, HttpMethod.PUT);
    return JacksonUtils.toObj(result);
}
```
实际上，就是调用了Nacos服务端提供的"/nacos/v1/ns/instance/beat"服务。

在客户端的常量类Constants中定义了心跳相关的默认参数：

```
static {
    DEFAULT_HEART_BEAT_TIMEOUT = TimeUnit.SECONDS.toMillis(15L);
    DEFAULT_IP_DELETE_TIMEOUT = TimeUnit.SECONDS.toMillis(30L);
    DEFAULT_HEART_BEAT_INTERVAL = TimeUnit.SECONDS.toMillis(5L);
}
```
这样就呼应了最开始说的Nacos健康检查机制的几个时间维度。

## 服务端接收心跳

分析客户端的过程中已经可以看出请求的是/nacos/v1/ns/instance/beat这个服务。Nacos服务端是在Naming项目中的InstanceController中实现的。

```
@CanDistro
@PutMapping("/beat")
@Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
public ObjectNode beat(HttpServletRequest request) throws Exception {

    // ...
    Instance instance = serviceManager.getInstance(namespaceId, serviceName, clusterName, ip, port);

    if (instance == null) {
        // ...
        instance = new Instance();
        instance.setPort(clientBeat.getPort());
        instance.setIp(clientBeat.getIp());
        instance.setWeight(clientBeat.getWeight());
        instance.setMetadata(clientBeat.getMetadata());
        instance.setClusterName(clusterName);
        instance.setServiceName(serviceName);
        instance.setInstanceId(instance.getInstanceId());
        instance.setEphemeral(clientBeat.isEphemeral());

        serviceManager.registerInstance(namespaceId, serviceName, instance);
    }

    Service service = serviceManager.getService(namespaceId, serviceName);
    // ...
    service.processClientBeat(clientBeat);
    // ...
    return result;
}
```
服务端在接收到请求时，主要做了两件事：第一，如果发送心跳的实例不存在，则将其进行注册；第二，调用其Service的processClientBeat方法进行心跳处理。

processClientBeat方法实现如下：

```
public void processClientBeat(final RsInfo rsInfo) {
    ClientBeatProcessor clientBeatProcessor = new ClientBeatProcessor();
    clientBeatProcessor.setService(this);
    clientBeatProcessor.setRsInfo(rsInfo);
    HealthCheckReactor.scheduleNow(clientBeatProcessor);
}
```
ClientBeatProcessor同样是一个实现了Runnable的Task，通过HealthCheckReactor定义的scheduleNow方法进行立即执行。

scheduleNow方法实现：

```
public static ScheduledFuture<?> scheduleNow(Runnable task) {
    return GlobalExecutor.scheduleNamingHealth(task, 0, TimeUnit.MILLISECONDS);
}
```
再来看看ClientBeatProcessor中对具体任务的实现：

```
@Override
public void run() {
    Service service = this.service;
    // logging    
    String ip = rsInfo.getIp();
    String clusterName = rsInfo.getCluster();
    int port = rsInfo.getPort();
    Cluster cluster = service.getClusterMap().get(clusterName);
    List<Instance> instances = cluster.allIPs(true);
    
    for (Instance instance : instances) {
        if (instance.getIp().equals(ip) && instance.getPort() == port) {
            // logging
            instance.setLastBeat(System.currentTimeMillis());
            if (!instance.isMarked()) {
                if (!instance.isHealthy()) {
                    instance.setHealthy(true);
                    // logging
                    getPushService().serviceChanged(service);
                }
            }
        }
    }
}
```
在run方法中先检查了发送心跳的实例和IP是否一致，如果一致则更新最后一次心跳时间。同时，如果该实例之前未被标记且处于不健康状态，则将其改为健康状态，并将变动通过PushService提供事件机制进行发布。事件是由Spring的ApplicationContext进行发布，事件为ServiceChangeEvent。

通过上述心跳操作，Nacos服务端的实例的健康状态和最后心跳时间已经被刷新。那么，如果没有收到心跳时，服务器端又是如何判断呢？

## 服务端心跳检查

客户端发起心跳，服务器端来检查客户端的心跳是否正常，或者说对应的实例中的心跳更新时间是否正常。

服务器端心跳的触发是在服务实例注册时触发的，同样在InstanceController中，register注册实现如下：

```
@CanDistro
@PostMapping
@Secured(parser = NamingResourceParser.class, action = ActionTypes.WRITE)
public String register(HttpServletRequest request) throws Exception {
    // ...
    final Instance instance = parseInstance(request);

    serviceManager.registerInstance(namespaceId, serviceName, instance);
    return "ok";
}
```
ServiceManager#registerInstance实现代码如下：

```
public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
    
    createEmptyService(namespaceId, serviceName, instance.isEphemeral());
    // ...
}
```
心跳相关实现在第一次创建空的Service中实现，最终会调到如下方法：

```
public void createServiceIfAbsent(String namespaceId, String serviceName, boolean local, Cluster cluster)
        throws NacosException {
    Service service = getService(namespaceId, serviceName);
    if (service == null) {
        
        Loggers.SRV_LOG.info("creating empty service {}:{}", namespaceId, serviceName);
        service = new Service();
        service.setName(serviceName);
        service.setNamespaceId(namespaceId);
        service.setGroupName(NamingUtils.getGroupName(serviceName));
        // now validate the service. if failed, exception will be thrown
        service.setLastModifiedMillis(System.currentTimeMillis());
        service.recalculateChecksum();
        if (cluster != null) {
            cluster.setService(service);
            service.getClusterMap().put(cluster.getName(), cluster);
        }
        service.validate();
        
        putServiceAndInit(service);
        if (!local) {
            addOrReplaceService(service);
        }
    }
}
```
在putServiceAndInit方法中对Service进行初始化：

```
private void putServiceAndInit(Service service) throws NacosException {
    putService(service);
    service = getService(service.getNamespaceId(), service.getName());
    service.init();
    consistencyService
            .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), true), service);
    consistencyService
            .listen(KeyBuilder.buildInstanceListKey(service.getNamespaceId(), service.getName(), false), service);
    Loggers.SRV_LOG.info("[NEW-SERVICE] {}", service.toJson());
}
```
service.init()方法实现：

```
public void init() {
    HealthCheckReactor.scheduleCheck(clientBeatCheckTask);
    for (Map.Entry<String, Cluster> entry : clusterMap.entrySet()) {
        entry.getValue().setService(this);
        entry.getValue().init();
    }
}
```
HealthCheckReactor#scheduleCheck方法实现：

```
public static void scheduleCheck(ClientBeatCheckTask task) {
    futureMap.computeIfAbsent(task.taskKey(),
            k -> GlobalExecutor.scheduleNamingHealth(task, 5000, 5000, TimeUnit.MILLISECONDS));
}
```
延迟5秒执行，每5秒检查一次。

在init方法的第一行便可以看到执行健康检查的Task，具体Task是由ClientBeatCheckTask来实现，对应的run方法核心代码如下：

```
@Override
public void run() {
    // ...        
    List<Instance> instances = service.allIPs(true);
    
    // first set health status of instances:
    for (Instance instance : instances) {
        if (System.currentTimeMillis() - instance.getLastBeat() > instance.getInstanceHeartBeatTimeOut()) {
            if (!instance.isMarked()) {
                if (instance.isHealthy()) {
                    instance.setHealthy(false);
                    // logging...
                    getPushService().serviceChanged(service);
                    ApplicationUtils.publishEvent(new InstanceHeartbeatTimeoutEvent(this, instance));
                }
            }
        }
    }
    
    if (!getGlobalConfig().isExpireInstance()) {
        return;
    }
    
    // then remove obsolete instances:
    for (Instance instance : instances) {
        
        if (instance.isMarked()) {
            continue;
        }
        
        if (System.currentTimeMillis() - instance.getLastBeat() > instance.getIpDeleteTimeout()) {
            // delete instance
            deleteIp(instance);
        }
    }
}
```
在第一个for循环中，先判断当前时间与上次心跳时间的间隔是否大于超时时间。如果实例已经超时，且为被标记，且健康状态为健康，则将健康状态设置为不健康，同时发布状态变化的事件。

在第二个for循环中，如果实例已经被标记则跳出循环。如果未标记，同时当前时间与上次心跳时间的间隔大于删除IP时间，则将对应的实例删除。

## 小结

通过本文的源码分析，我们从Spring Cloud开始，追踪到Nacos Client中的心跳时间，再追踪到Nacos服务端接收心跳的实现和检查实例是否健康的实现。想必通过整个源码的梳理，你已经对整个Nacos心跳的实现有所了解。关注我，持续更新Nacos的最新干货。


## Nacos系列

- 《[Spring Cloud集成Nacos服务发现源码解析？翻了三套源码，保质保鲜！](https://mp.weixin.qq.com/s/JuzRf2E4AvdoQW4hrfJKVg)》
- 《[要学习微服务的服务发现？先来了解一些科普知识吧](https://mp.weixin.qq.com/s/mZ-IVHDaJUOBykpBzVr5og)》
- 《[微服务的灵魂摆渡者——Nacos，来一篇原理全攻略](https://mp.weixin.qq.com/s/BIPdW34VKvp_Ced3nzUVvQ)》
- 《[你也对阅读源码感兴趣，说说我是如何阅读Nacos源码的](https://mp.weixin.qq.com/s/4pVWPRKGwy9MpEzGL4rgLA)》
- 《[学习Nacos？咱先把服务搞起来，实战教程]( https://mp.weixin.qq.com/s/CflYusFuOy5QstWQFLdWwg)》
- 《[微服务之：服务挂的太干脆，Nacos还没反应过来，怎么办？](https://mp.weixin.qq.com/s/fDtcQD1EL-NgVV1BMiPx4g)》
- 《[微服务之吐槽一下Nacos日志的疯狂输出](https://mp.weixin.qq.com/s/SHd3SHlaH_uFyDFXSMWCiw)》
- 《[一个实例，轻松演示Spring Cloud集成Nacos实例](https://mp.weixin.qq.com/s/3EQ1M_Z5Lk5Pyaisg6qp-w)》
