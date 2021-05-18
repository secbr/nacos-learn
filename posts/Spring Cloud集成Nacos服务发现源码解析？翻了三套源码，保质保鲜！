# Spring Cloud集成Nacos服务发现源码解析？翻了三套源码，保质保鲜！

前面文章我们介绍了Nacos的功能及设计架构，这篇文章就以Nacos提供的服务注册功能为主线，来讲解Nacos的客户端是如何在Spring Cloud进行集成和实现的。

本会配合源码分析、流程图整理、核心API解析等维度来让大家深入浅出、系统的来学习。

### Spring Boot的自动注册

故事要从头Spring Boot的自动注入开始。很多朋友大概都了解过Spring Boot的自动配置功能，而Spring Cloud又是基于Spring Boot框架的。

因此，在学习Nacos注册业务之前，我们先来回顾一下Spring Boot的自动配置原理，这也是学习的入口。

Spring Boot通过@EnableAutoConfiguration注解，将所有符合条件的@Configuration配置都加载到当前SpringBoot创建并使用的IoC容器。

上述过程是通过@Import(AutoConfigurationImportSelector.class)导入的配置功能，AutoConfigurationImportSelector中的方法getCandidateConfigurations，得到待配置的class的类名集合，即所有需要进行自动配置的（xxxAutoConfiguration）类，这些类配置于META-INF/spring.factories文件中。

最后，根据这些全限定名类上的注解，如：OnClassCondition、OnBeanCondition、OnWebApplicationCondition条件化的决定要不要自动配置。

了解了Spring Boot的基本配置之后，我们来看看Nacos对应的自动配置在哪里。

### Spring Cloud中的Nacos自动配置

查看Spring Cloud的项目依赖，本人引入依赖对应的jar包为spring-cloud-starter-alibaba-nacos-discovery-2021.1.jar；

对应的pom依赖为：

```
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```
查看jar包中META-INF/spring.factories文件的内容：
```
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration,\
  com.alibaba.cloud.nacos.endpoint.NacosDiscoveryEndpointAutoConfiguration,\
  com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration,\
  com.alibaba.cloud.nacos.discovery.NacosDiscoveryClientConfiguration,\
  com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClientConfiguration,\
  com.alibaba.cloud.nacos.discovery.configclient.NacosConfigServerAutoConfiguration,\
  com.alibaba.cloud.nacos.NacosServiceAutoConfiguration
org.springframework.cloud.bootstrap.BootstrapConfiguration=\
  com.alibaba.cloud.nacos.discovery.configclient.NacosDiscoveryClientConfigServiceBootstrapConfiguration
```
可以看到EnableAutoConfiguration类对应了一系列的Nacos自动配置类。

其中NacosServiceRegistryAutoConfiguration是用来封装实例化Nacos注册流程所需组件的，装载了对三个对象NacosServiceRegistry、NacosRegistration、NacosAutoServiceRegistration，这三个对象整体都是为了Nacos服务注册使用的。

```
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
@ConditionalOnNacosDiscoveryEnabled
@ConditionalOnProperty(value = "spring.cloud.service-registry.auto-registration.enabled",
		matchIfMissing = true)
@AutoConfigureAfter({ AutoServiceRegistrationConfiguration.class,
		AutoServiceRegistrationAutoConfiguration.class,
		NacosDiscoveryAutoConfiguration.class })
public class NacosServiceRegistryAutoConfiguration {

	@Bean
	public NacosServiceRegistry nacosServiceRegistry(
			NacosDiscoveryProperties nacosDiscoveryProperties) {
		return new NacosServiceRegistry(nacosDiscoveryProperties);
	}

	@Bean
	@ConditionalOnBean(AutoServiceRegistrationProperties.class)
	public NacosRegistration nacosRegistration(
			ObjectProvider<List<NacosRegistrationCustomizer>> registrationCustomizers,
			NacosDiscoveryProperties nacosDiscoveryProperties,
			ApplicationContext context) {
		return new NacosRegistration(registrationCustomizers.getIfAvailable(),
				nacosDiscoveryProperties, context);
	}

	@Bean
	@ConditionalOnBean(AutoServiceRegistrationProperties.class)
	public NacosAutoServiceRegistration nacosAutoServiceRegistration(
			NacosServiceRegistry registry,
			AutoServiceRegistrationProperties autoServiceRegistrationProperties,
			NacosRegistration registration) {
		return new NacosAutoServiceRegistration(registry,
				autoServiceRegistrationProperties, registration);
	}
}
```
其中NacosServiceRegistry封装的就是注册流程，它继承自ServiceRegistry：
```
public class NacosServiceRegistry implements ServiceRegistry<Registration> {...}
```
查看该类源码，可以看到该类中实现了服务注册、注销、关闭、设置状态、获取状态5个功能。

我们要追踪的服务注册功能，便是通过它提供的register方法来实现的。

至此，我们可以梳理一下Nacos客户端在Spring Cloud中集成并实例化的处理流程。
![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-source-01.jpg)

### Spring Cloud的ServiceRegistry接口

上面提到NacosServiceRegistry集成自ServiceRegistry，那么ServiceRegistry又是何方神圣呢？

ServiceRegistry接口是Spring Cloud的类，来看一下ServiceRegistry接口的定义：

```
public interface ServiceRegistry<R extends Registration> {

	void register(R registration);
	void deregister(R registration);
	void close();
	void setStatus(R registration, String status);
	<T> T getStatus(R registration);
}
```
可以看出ServiceRegistry接口中定义了服务注册、注销、关闭、设置状态、获取状态五个接口。

如果看其他服务发现框架对Spring Cloud进行集成时，基本上都是实现的这个接口。也就是说，ServiceRegistry是Spring Cloud提供的一个服务发现框架集成的规范。对应的框架安装规范实现对应的功能即可进行集成。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-source-02.jpg)

我们可以看到Eureka、Zookeeper、Consul在Spring Cloud中集成也都是实现了该接口，同时，如果你需要自定义服务发现功能，也可以通过实现该接口来达到目的。

### NacosServiceRegistry服务注册实现

暂且不关注其他的辅助类，直接来看NacosServiceRegistry#register方法，它提供了服务注册的核心业务逻辑实现。

我们把该类的辅助判断去掉，直接展示最核心的代码如下：

```
@Override
public void register(Registration registration) {

    // 获取NamingService
	NamingService namingService = namingService();
	String serviceId = registration.getServiceId();
	String group = nacosDiscoveryProperties.getGroup();

    // 构造实例，封装信息来源于配置属性
	Instance instance = getNacosInstanceFromRegistration(registration);
    // 将实例进行注册
	namingService.registerInstance(serviceId, group, instance);
}
```
上述代码中NamingService已经属于Nacos Client项目提供的API支持了。

关于Nacos Client的API流程查看，可直接查看Nacos对应的源码，NamingService#registerInstance方法对应的流程图整理如下：
![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-source-03.jpg)

上述流程图还可以继续细化，这个我们在后续章节中进行专门讲解，这里大家知道大概的调用流程即可。

### Spring Cloud服务注册链路

下面我们来梳理一下Spring Cloud是如何进行服务注册的，其中流程的前三分之二部分几乎所有的服务注册框架都是一样的流程，只有最后一部分进行实例注册时会调用具体的框架来进行实现。

直接来看整个调用的链路图：
![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-source-04.jpg)

图中不同的颜色代表这不同的框架，灰色表示业务代码，浅绿色表示SpringBoot框架，深绿色表示Spring框架，浅橙色表示SpringCloud框架，其中这一部分也包含了依赖的Nacos组件部分，最后浅紫色代表着Nacos Client的包。

核心流程分以下几步：

第一步，SpringBoot在启动main方法时调用到Spring的核心方法refresh；

第二步，在Spring中实例化了WebServerStartStopLifecycle对象。

重点说一下WebServerStartStopLifecycle对象，它的start方法被调用时会发布一个ServletWebServerInitializedEvent事件类，这个事件类继承自WebServerInitializedEvent。后面用来处理服务注册的类AbstractAutoServiceRegistration同时也是一个监听器，专门用来监听WebServerInitializedEvent事件。

第三步，AbstractApplicationContext的finishRefresh中会间接调用DefaultLifecycleProcessor的startBeans方法，进而调用了WebServerStartStopLifecycle的start方法。就像上面说的，触发了ServletWebServerInitializedEvent事件的发布。

第四步，AbstractAutoServiceRegistration监听到对应的事件，然后基于Spring Cloud定义的ServiceRegistry接口进行服务注册。

上面的描述省略了一些部分细节，但整个流程基本上就是SpringBoot在启动时发布了一个事件，Spring Cloud监听到对应的事件，然后进行服务的注册。

### 小结

为了这篇文章，肝了好几天。Spring Cloud源码、Spring Boot源码、Nacos源码都翻了个遍。最终为大家分享了Nacos或者说是Spring Cloud中服务发现的实现机制及流程。

之所以写这篇文章，也是想倡导大家更多的走进源码，而不是仅仅在使用。你学到了吗？

PS：笔者计划在较长一段时间内研究Spring Cloud微服务系列源码，像这篇文章一样深入底层源码。Nacos（服务发现）只是开设，如果你对此方面感兴趣，添加微信好友，备注Nacos，在人数足够时会建立相关交流群。


