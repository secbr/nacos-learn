# 微服务之：服务挂的太干脆，Nacos还没反应过来，怎么办？

## 前言

我们知道通过Nacos等注册中心可以实现微服务的治理。但引入了Nacos之后，真的就像理想中那样所有服务都由Nacos来完美的管理了吗？Too young，too simple！

今天这篇文章就跟大家聊聊，当服务异常宕机，Nacos还未反应过来时，可能会发生的状况以及现有的解决方案。

## Nacos的健康检查

故事还要从Nacos对服务实例的健康检查说起。

Nacos目前支持临时实例使用心跳上报方式维持活性。Nacos客户端会维护一个定时任务，每隔5秒发送一次心跳请求，以确保自己处于活跃状态。

Nacos服务端在15秒内如果没收到客户端的心跳请求，会将该实例设置为不健康，在30秒内没收到心跳，会将这个临时实例摘除。

## 如果服务突然挂掉

在正常业务场景下，如果关闭掉一个服务实例，默认情况下会在关闭之前主动调用注销接口，将Nacos服务端注册的实例清除掉。

如果服务实例还没来得注销已经被干掉，比如正常kill一个应用，应用会处理完手头的事情再关闭，但如果使用kill -9来强制杀掉，就会出现无法注销的情况。

针对这种意外情况，服务注销接口是无法被正确调用的，此时就需要健康检查来确保该实例被删除。

通过上面分析的Nacos健康检查机制，我们会发现服务突然挂掉之后，会有15秒的间隙。在这段时间，Nacos服务端还没感知到服务挂掉，依旧将该服务提供给客户端使用。

此时，必然会有一部分请求被分配到异常的实例上。针对这种情况，又该如何处理呢？如何确保服务不影响正常的业务呢？

## 自定义心跳周期

针对上面的问题，我们最容易想到的是解决方案就是缩短默认的健康检查时间。

原本15秒才能发现服务异常，标记为不健康，那么是否可以将其缩短呢？这样错误影响的范围便可以变小，变得可控。

针对此，Nacos 1.1.0之后提供了自定义心跳周期的配置。如果你基于客户端进行操作，在创建实例时，可在实例的metadata数据中进行心跳周期、健康检查过期时间及删除实例时间的配置。

相关示例如下：

```
String serviceName = randomDomainName();

Instance instance = new Instance();
instance.setIp("1.1.1.1");
instance.setPort(9999);
Map<String, String> metadata = new HashMap<String, String>();
// 设置心跳的周期，单位为毫秒
metadata.put(PreservedMetadataKeys.HEART_BEAT_INTERVAL, "3000");
// 设置心跳超时时间，单位为毫秒；服务端6秒收不到客户端心跳，会将该客户端注册的实例设为不健康：
metadata.put(PreservedMetadataKeys.HEART_BEAT_TIMEOUT, "6000");
// 设置实例删除的超时时间，单位为毫秒；即服务端9秒收不到客户端心跳，会将该客户端注册的实例删除：
metadata.put(PreservedMetadataKeys.IP_DELETE_TIMEOUT, "9000");
instance.setMetadata(metadata);

naming.registerInstance(serviceName, instance);
```

如果是基于Spring Cloud Alibaba的项目，可通过如下方式配置：

```
spring:
  application:
    name: user-service-provider
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        heart-beat-interval: 1000 #心跳间隔。单位为毫秒。
        heart-beat-timeout: 3000 #心跳暂停。单位为毫秒。
        ip-delete-timeout: 6000 #Ip删除超时。单位为毫秒。
```

在某些Spring Cloud版本中，上述配置可能无法生效。也可以直接配置metadata的数据。配置方式如下：

```
spring:
  application:
    name: user-service-provider
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        metadata:
          preserved.heart.beat.interval: 1000 #心跳间隔。时间单位:毫秒。
          preserved.heart.beat.timeout: 3000 #心跳暂停。时间单位:毫秒。 即服务端6秒收不到客户端心跳，会将该客户端注册的实例设为不健康；
          preserved.ip.delete.timeout: 6000 #Ip删除超时。时间单位:秒。即服务端9秒收不到客户端心跳，会将该客户端注册的实例删除；
```
其中第一种配置，感兴趣的朋友可以看一下NacosServiceRegistryAutoConfiguration中相关组件的实例化。在某些版本中由于NacosRegistration和NacosDiscoveryProperties实例化的顺序问题会导致配置未生效。此时可考虑第二种配置形式。

上面的配置项，最终会在NacosServiceRegistry在进行实例注册时通过getNacosInstanceFromRegistration方法进行封装：
```
private Instance getNacosInstanceFromRegistration(Registration registration) {
		Instance instance = new Instance();
		instance.setIp(registration.getHost());
		instance.setPort(registration.getPort());
		instance.setWeight(nacosDiscoveryProperties.getWeight());
		instance.setClusterName(nacosDiscoveryProperties.getClusterName());
		instance.setEnabled(nacosDiscoveryProperties.isInstanceEnabled());
		// 设置Metadata
		instance.setMetadata(registration.getMetadata());
		instance.setEphemeral(nacosDiscoveryProperties.isEphemeral());
		return instance;
	}
```
其中setMetadata方法即是。

通过Nacos提供的心跳周期配置，再结合自身的业务场景，我们就可以选择最适合的心跳检测机制，尽最大可能避免对业务的影响。

这个方案看起来心跳周期越短越好，但这样会对Nacos服务端造成一定的压力。如果服务器允许，还是可以尽量缩短的。

## Nacos的保护阈值

在上述配置中，我们还要结合自身的项目情况考虑一下Nacos保护阈值的配置。

在Nacos中针对注册的服务实例有一个保护阈值的配置项。该配置项的值为0-1之间的浮点数。

本质上，保护阈值是⼀个⽐例值（当前服务健康实例数/当前服务总实例数）。

⼀般流程下，服务消费者要从Nacos获取可⽤实例有健康/不健康状态之分。Nacos在返回实例时，只会返回健康实例。

但在⾼并发、⼤流量场景会存在⼀定的问题。比如，服务A有100个实例，98个实例都处于不健康状态，如果Nacos只返回这两个健康实例的话。流量洪峰的到来可能会直接打垮这两个服务，进一步产生雪崩效应。

保护阈值存在的意义在于当服务A健康实例数/总实例数 < 保护阈值时，说明健康的实例不多了，保护阈值会被触发（状态true）。

Nacos会把该服务所有的实例信息（健康的+不健康的）全部提供给消费者，消费者可能访问到不健康的实例，请求失败，但这样也⽐造成雪崩要好。牺牲了⼀些请求，保证了整个系统的可⽤。

在上面的解决方案中，我们提到了可以自定义心跳周期，其中能够看到实例的状态会由健康、不健康和移除。这些参数的定义也要考虑到保护阈值的触发，避免雪崩效应的发生。

## SpringCloud的请求重试

即便上面我们对心跳周期进行了调整，但在某一实例发生故障时，还会有短暂的时间出现Nacos服务没来得及将异常实例剔除的情况。此时，如果消费端请求该实例，依然会出现请求失败。

为了构建更为健壮的应用系统，我们希望当请求失败的时候能够有一定策略的重试机制，而不是直接返回失败。这个时候就需要开发人来实现重试机制。

在微服务架构中，通常我们会基于Ribbon或Spring Cloud LoadBalancer来进行负载均衡处理。除了像Ribbon、Feign框架自身已经支持的请求重试和请求转移功能。Spring Cloud也提供了标准的loadbalancer相关配置。

关于Ribbon框架的使用我们在这里就不多说了，重点来看看Spring Cloud是如何帮我们实现的。

### 异常模拟

我们先来模拟一下异常情况，将上面讲到的先将上面的心跳周期调大，以方便测试。

然后启动两个provider和一个consumer服务，负载均衡基于Spring Cloud LoadBalancer来处理。此时通过consumer进行请求，你会发现LoadBalancer通过轮训来将请求均匀的分配到两个provider上（打印日志）。

此时，通过kill -9命令将其中一个provider关掉。此时，再通过consumer进行请求，会发现成功一次，失败一次，这样交替出现。

### 解决方案

我们通过Spring Cloud提供的LoadBalancerProperties配置类中定义的配置项来对重试机制进行配置，详细的配置项目可以对照该类的属性。

在consumer的application配置中添加retry相关配置：

```
spring:
  application:
    name: user-service-consumer
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    loadbalancer:
      retry:
        # 开启重试
        enabled: true
        # 同一实例最大尝试次数
        max-retries-on-same-service-instance: 1
        # 其他实例最大尝试次数
        max-retries-on-next-service-instance: 2
        # 所有操作开启重试（慎重使用，特别是POST提交，幂等性保障）
        retry-on-all-operations: true
```
上述配置中默认retry是开启的。

max-retries-on-same-service-instance指的是当前实例尝试的次数，包括第一次请求，这里配置为1，也就是第一次请求失败就转移到其他实例了。当然也可以配置大于1的数值，这样还会在当前实例再尝试一下。

max-retries-on-next-service-instance配置的转移请求其他实例时最大尝试次数。

retry-on-all-operations默认为false，也就是说只支持Get请求的重试。这里设置为true支持所有的重试。既然涉及到重试，就需要保证好业务的幂等性。

当进行上述配置之后，再次演示异常模拟，会发现即使服务挂掉，在Nacos中还存在，依旧可以正常进行业务处理。

关于Ribbon或其他同类组件也有类似的解决方案，大家可以相应调研一下。

### 解决方案的坑

在使用Spring Cloud LoadBalancer时其实有一个坑，你可能会遇到上述配置不生效的情况。这是为什么呢？

其实是因为依赖引入的问题，Spring Cloud LoadBalancer的重试机制是基于spring-retry的，如果没有引入对应的依赖，便会导致配置无法生效。而官方文档业务未给出说明。

```
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
```
另外，上述实例是基于Spring Cloud 2020.0.0版本，其他版本可能有不同的配置。


## 小结

在使用微服务的时候并不是将Spring Cloud的组件集成进去就完事了。这篇文章我们可以看到即便集成了Nacos，还会因为心跳机制来进行一些折中处理，比如调整心跳频次。

同时，即便调整了心跳参数，还需要利用其它组件来兼顾请求异常时的重试和防止系统雪崩的发生。关注一下吧，持续更新微服务系列实战内容。


