# 微服务架构：Nacos本地缓存 PK 微服务优雅下线

## 前言

在上篇文章《[微服务：剖析一下源码，Nacos的健康检查竟如此简单](https://mp.weixin.qq.com/s/jcnQOXuyMvo-uhc2XeP01Q)》中讲了当微服务突然挂掉的解放方案：调整健康检查周期和故障请求重试。朋友看了文章，建议再聊聊正常关闭服务时如何让微服务优雅下线。

为什么说是优雅下线？我们知道在分布式应用中为了满足CAP原则中的A（可用性），像Nacos、Eureka等注册中心的客户端都会进行实例列表的缓存。当正常关闭应用时，虽然可以主动调用注册中心进行注销，但这些客户端缓存的实例列表还是要等一段时间才会失效。

上述情况就有可能导致服务请求到已经被关闭的实例上，虽然通过重试机制可以解决掉这个问题，但这种解决方案会出现重试，在一定程度上会导致用户侧请求变慢。这时就需要进行优雅的下线操作了。

下面我们先从通常关闭进程的几种方式聊起。

## 方式一：基于kill命令

Spring Cloud本身对关闭服务是有支持的，当通过kill命令关闭进程时会主动调用Shutdown hook来进行当前实例的注销。使用方式：

```
kill Java进程ID
```
这种方式是借助Spring Cloud的Shutdown hook机制（本质是Spring Boot提供，Spring Cloud服务发现功能进行具体注销实现），在关闭服务之前会对Nacos、Eureka等服务进行注销。但这个注销只是告诉了注册中心，客户端的缓存可能需要等几秒（Nacos默认为5秒）之后才能感知到。

这种Shutdown hook机制不仅适用于kill命令，还适用于程序正常退出、使用System.exit()、终端使用Ctrl + C等。但不适用于kill -9 这样强制关闭或服务器宕机等场景。

这种方案虽然比直接挂掉要等15秒缩短了时间，相对好一些，但本质上并没有解决客户端缓存的问题，不建议使用。

## 方式二：基于/shutdown端点

在Spring Boot中，提供了/shutdown端点，基于此也可以实现优雅停机，但本质上与第一种方式相同，都是基于Shutdown hook来实现的。在处理完基于Shutdown hook的逻辑之后，也会进行服务的关闭，但同样面临客户端缓存的问题，因此，也不推荐使用。

这种方式首先需要在项目中引入对应的依赖：

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```
然后在项目中配置开启/shutdown端点：

```
management:
  endpoint:
    shutdown:
      enabled: true
  endpoints:
    web:
      exposure:
        include: shutdown
```
然后停服时请求对应的端点，这里采用curl命令示例：

```
curl -X http://实例服务地址/actuator/shutdown
```

## 方式三：基于/pause端点

Spring Boot同样提供了/pause端点（Spring Boot Actuator提供），通过/pause端点，可以将/health为UP状态的实例修改为Down状态。

基本操作就是在配置文件中进行pause端点的开启：

```
management:
  endpoint:
    # 启用pause端点
    pause:
      enabled: true
    # pause端点在某些版本下依赖restart端点
    restart:
      enabled: true
  endpoints:
    web:
      exposure:
        include: pause,restart
```

然后发送curl命令，即可进行服务的终止。注意这里需要采用POST请求。

关于/pause端点的使用，不同的版本差异很大。笔者在使用Spring Boot 2.4.2.RELEASE版本时发现根本无法生效，查了Spring Boot和Spring Cloud项目的Issues发现，这个问题从2.3.1.RELEASE就存在。目前看应该是在最新版本中Web Server的管理改为SmartLifecycle的原因，而Spring Cloud对此貌似放弃了支持（有待考察），最新的版本调用/pause端点无任何反应。

鉴于上述版本变动过大的原因，不建议使用/pause端点进行微服务的下线操作，但使用/pause端点的整个思路还是值得借鉴的。

基本思路就是：当调用/pause端点之后，微服务的状态会从UP变为DOWN，而服务本身还是可以正常提供服务。当微服务被标记为DOWN状态之后，会从注册中心摘除，等待一段时间（比如5秒），当Nacos客户端缓存的实例列表更新了，再进行停服处理。

这个思路的核心就是：先将微服务的流量切换掉，然后再关闭或重新发布。这就解决了正常发布时客户端缓存实例列表的问题。

基于上述思路，其实自己也可以实现相应的功能，比如提供一个Controller，先调用该Controller中的方法将当前实例从Nacos中注销，然后等待5秒，再通过脚本或其他方式将服务关闭掉。

## 方式四：基于/service-registry端点

方式三中提到的方案如果Spring Cloud能够直接支持，那就更好了。这不，Spring Cloud提供了/service-registry端点。但从名字就可以知道专门针对服务注册实现的一个端点。

在配置文件中开启/service-registry端点：

```
management:
  endpoints:
    web:
      exposure:
        include: service-registry
      base-path: /actuator
  endpoint:
    serviceregistry:
      enabled: true
```

访问http://localhost:8081/actuator 端点可以查看到开启了如下端点：


```
{
    "_links": {
        "self": {
            "href": "http://localhost:8081/actuator",
            "templated": false
        },
        "serviceregistry": {
            "href": "http://localhost:8081/actuator/serviceregistry",
            "templated": false
        }
    }
}
```
通过curl命令来进行服务状态的修改：


```
curl -X "POST" "http://localhost:8081/actuator/serviceregistry?status=DOWN" -H "Content-Type: application/vnd.spring-boot.actuator.v2+json;charset=UTF-8"
```
执行上述命令之前，查看Nacos对应实例状态为：


![image.png](http://www.choupangxia.com/wp-content/uploads/2021/07/nacos-server-01.jpg)

可以看到实例详情中的按钮为“下线”也就是说目前处于UP状态。当执行完上述curl命令之后，实例详情中的按钮为“上线”，说明实例已经下线了。


![image.png](http://www.choupangxia.com/wp-content/uploads/2021/07/nacos-server-02.jpg)

上述命令就相当于我们在Nacos管理后台手动的操作了实例的上下线。

当然，上述情况是基于Spring Cloud和Nacos的模式实现的，本质上Spring Cloud是定义了一个规范，比如所有的注册中心都需要实现ServiceRegistry接口，同时基于ServiceRegistry这个抽象还定义了通用的Endpoint：

```
@Endpoint(id = "serviceregistry")
public class ServiceRegistryEndpoint {

   private final ServiceRegistry serviceRegistry;

   private Registration registration;

   public ServiceRegistryEndpoint(ServiceRegistry<?> serviceRegistry) {
      this.serviceRegistry = serviceRegistry;
   }

   public void setRegistration(Registration registration) {
      this.registration = registration;
   }

   @WriteOperation
   public ResponseEntity<?> setStatus(String status) {
      Assert.notNull(status, "status may not by null");

      if (this.registration == null) {
         return ResponseEntity.status(HttpStatus.NOT_FOUND).body("no registration found");
      }

      this.serviceRegistry.setStatus(this.registration, status);
      return ResponseEntity.ok().build();
   }

   @ReadOperation
   public ResponseEntity getStatus() {
      if (this.registration == null) {
         return ResponseEntity.status(HttpStatus.NOT_FOUND).body("no registration found");
      }

      return ResponseEntity.ok().body(this.serviceRegistry.getStatus(this.registration));
   }

}
```
我们上面调用的Endpoint便是通过上面代码实现的。所以不仅Nacos，只要基于Spring Cloud集成的注册中心，本质上都是支持这种方式的服务下线的。

## 小结

很多项目都逐步在进行微服务化改造，但一旦因为微服务系统，将面临着更复杂的情况。本篇文章重点基于Nacos在Spring Cloud体系中优雅下线来为大家剖析了一个微服务实战中常见的问题及解决方案。你是否在使用微服务，你又是否注意到这一点了？想学更多微服务实战，啥也不说，关注吧。

## Nacos系列

- 《[Spring Cloud集成Nacos服务发现源码解析？翻了三套源码，保质保鲜！](https://mp.weixin.qq.com/s/JuzRf2E4AvdoQW4hrfJKVg)》
- 《[要学习微服务的服务发现？先来了解一些科普知识吧](https://mp.weixin.qq.com/s/mZ-IVHDaJUOBykpBzVr5og)》
- 《[微服务的灵魂摆渡者——Nacos，来一篇原理全攻略](https://mp.weixin.qq.com/s/BIPdW34VKvp_Ced3nzUVvQ)》
- 《[你也对阅读源码感兴趣，说说我是如何阅读Nacos源码的](https://mp.weixin.qq.com/s/4pVWPRKGwy9MpEzGL4rgLA)》
- 《[学习Nacos？咱先把服务搞起来，实战教程]( https://mp.weixin.qq.com/s/CflYusFuOy5QstWQFLdWwg)》
- 《[微服务之：服务挂的太干脆，Nacos还没反应过来，怎么办？](https://mp.weixin.qq.com/s/fDtcQD1EL-NgVV1BMiPx4g)》
- 《[微服务之吐槽一下Nacos日志的疯狂输出](https://mp.weixin.qq.com/s/SHd3SHlaH_uFyDFXSMWCiw)》
- 《[一个实例，轻松演示Spring Cloud集成Nacos实例](https://mp.weixin.qq.com/s/3EQ1M_Z5Lk5Pyaisg6qp-w)》
- 《[微服务：剖析一下源码，Nacos的健康检查竟如此简单](https://mp.weixin.qq.com/s/jcnQOXuyMvo-uhc2XeP01Q)》
