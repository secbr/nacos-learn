# 一个实例，轻松演示Spring Cloud集成Nacos实例

### 前言

学习一个技术框架，最快速的手段就是将其集成到项目中，体验一下它的功能。在这个过程中，你还踩到很多坑。而排坑的过程，又是一次能力的提升。

前面我们写了一些列Nacos的文章，经过《[学习Nacos？咱先把服务搞起来，实战教程]( https://mp.weixin.qq.com/s/CflYusFuOy5QstWQFLdWwg)》的介绍，我们已经可以把Nacos Server给启动起来了。

这篇文章，我们就来学习一下如何将Nacos集成到Spring Cloud项目中，同时实例演示一下，基于Nacos的微服务之间的两种调用形式。

### 集成与版本

为了演示这个案例，大家首先要将Nacos Server跑起来。同时会构建两个微服务：服务提供方（Provider）和服务消费方（Consumer）。然后，通过两个服务之间的调用及配合查看Nacos Server中的注册信息来进行验证。

我们知道，Nacos隶属于Spring Cloud Alibaba系列中的组件。所以，在进行集成之前，有一件事一定要注意，那就是要确保Spring Cloud、Spring Boot、Spring Cloud Alibaba版本的一致。不然发生一些莫名其妙的异常。

关于版本信息可以在https://spring.io/projects/spring-cloud中进行查看。

这里采用Spring Boot的版本为2.4.2，Spring Cloud采用2020.0.0、Spring Cloud Alibaba采用2021.1。如你采用其他版本，一定确保对照关系。

### Nacos服务提供者


#### 依赖配置

创建项目Spring Boot的项目spring-cloud-alibaba-nacos-provider1，在pom文件中添加定义依赖的版本限制：
```
<properties>
    <java.version>1.8</java.version>
    <spring-boot.version>2.4.2</spring-boot.version>
    <spring-cloud.version>2020.0.0</spring-cloud.version>
    <cloud-alibaba.version>2021.1</cloud-alibaba.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```
然后添加依赖：
```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```
其中actuator为健康检查依赖包，nacos-discovery为服务发现的依赖包。

#### 配置文件

提供者添加配置（application.yml）
```
server:
  port: 8081

spring:
  application:
    name: user-service-provider
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```
其中Nacos Server的地址和端口号默认是127.0.0.1:8848。name用来指定此服务的名称，消费者可通过注册的这个名称来进行请求。

#### 业务代码

在编写业务代码之前，我们先来看一下提供者的启动类：

```
// 版本不同，低版本需要明确使用@EnableDiscoveryClient注解
//@EnableDiscoveryClient
@SpringBootApplication
public class NacosProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(NacosProviderApplication.class, args);
    }
}
```
注意上面的注释部分，此版本已经不需要@EnableDiscoveryClient注解了，而较低的版本需要添加对应的注解。

下面新建一个UserController服务:

```
@RestController
@RequestMapping("/user")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    @GetMapping("/getUserById")
    public UserDetail getUserById(Integer userId) {
        logger.info("查询用户信息，userId={}", userId);
        UserDetail detail = new UserDetail();
        if (userId == 1) {
            detail.setUserId(1);
            detail.setUsername("Tom");
        } else {
            detail.setUserId(2);
            detail.setUsername("Other");
        }
        return detail;
    }
}
```
其中用到的实体类UserDetail为：

```
public class UserDetail {

    private Integer userId;

    private String username;

    // 省略getter/setter
}
```

然后启动服务，查看Nacos Server，会发现已经成功注册。

### Nacos服务消费者

消费者的创建与提供者基本一致，唯一不同的是调用相关的功能。

#### 创项目

创建Spring Boot项目spring-cloud-alibaba-nacos-consumer1，pom中的依赖与提供者基本一致，但还需要在它的基础上增加两个依赖：

```
<!-- consumer需要额外添加负载均衡的依赖-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-loadbalancer</artifactId>
</dependency>
<!-- 基于Feign框架进行调用-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```
其中loadbalancer是用来做服务调用负载均衡的，如果不添加此依赖，在调用的过程中会出现如下一次：

```
java.net.UnknownHostException: user-provider
    at java.net.AbstractPlainSocketImpl.connect(AbstractPlainSocketImpl.java:196) ~[na:1.8.0_271]
    at java.net.SocksSocketImpl.connect(SocksSocketImpl.java:394) ~[na:1.8.0_271]
    at java.net.Socket.connect(Socket.java:606) ~[na:1.8.0_271]
    at java.net.Socket.connect(Socket.java:555) ~[na:1.8.0_271]
```
而openfeign是用来实现基于feign框架的微服务调用，也就是让服务之间的调用更加方便。这个框架是可选的，如果你想基于RestTemplate方式进行调用，则不需要此框架的依赖。

#### 配置文件

消费者添加配置（application.yml）：

```
spring:
  application:
    name: user-service-consumer
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848

# 消费者将要去访问的微服务名称（注册成功进nacos的微服务提供者）
service-url:
  nacos-user-service: http://user-service-provider
```
同样server-addr指定注册Nacos Server的地址和端口。而配置中定义的service-url中便用到了服务提供者的服务名称user-service-provider。

#### 业务代码

关于启动类上的注解，与提供者一样，如果根据使用的版本决定是否使用@EnableDiscoveryClient注解。

创建UserController：

```
@RestController
@RequestMapping("/order")
public class UserController {

    @Resource
    private UserFeignService userFeignService;

    @Resource
    private RestTemplate restTemplate;

    @Value("${service-url.nacos-user-service}")
    private String userProvider;

    @GetMapping("getUserInfo")
    public UserDetail getUserInfo() {
        int userId = 1;
        ResponseEntity<UserDetail> result = restTemplate.getForEntity(userProvider + "/user/getUserById?userId=" + userId, UserDetail.class);
        return result.getBody();
    }

    @GetMapping("getUserInfo1")
    public UserDetail getUserInfoByFeign() {
        return userFeignService.getUserById(2);
    }

}

```
上述代码中展示了两种方式的请求，其中注入的RestTemplate和getUserInfo方法是一组，注入的UserFeignService和getUserInfoByFeign方法是一组。前者是基于RestTemplate方式请求，后者是基于Feign框架的模式进行请求的。

先来看基于RestTemplate方式的配置，需要先来实例化一下RestTemplate：
```
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

}
```
注意，这里使用了@LoadBalanced注解，RestTemplateCustomizer会给标有@LoadBalance的RestTemplate添加一个拦截器，拦截器的作用就是对请求的URI进行转换获取到具体应该请求哪个服务实例ServiceInstance。如果缺少这个注解，也会报上面提到的异常。

基于Feign的模式对应的UserFeignService如下：

```
@FeignClient(name = "user-service-provider")
public interface UserFeignService {

    /**
     * 基于Feign的接口调用
     *
     * @param userId 用户ID
     * @return UserDetail
     */
    @GetMapping(value = "/user/getUserById")
    UserDetail getUserById(@RequestParam Integer userId);
}
```
其中@FeignClient通过name属性指定调用微服务的名称，下面定义的方法则对应提供者的接口。

启动服务，查看Nacos Server的注册情况。

### 结果验证

此时，本地分别请求两个URL地址：

```
http://localhost:8080/order/getUserInfo
http://localhost:8080/order/getUserInfo1
```
访问一下，可以成功的返回结果：

```
// getUserInfo对应结果
{
"userId": 1,
"username": "Tom"
}
// getUserInfo1对应结果
{
"userId": 2,
"username": "Other"
}
```
至此，Spring Cloud集成Nacos实例演示完毕，完整的源代码地址：https://github.com/secbr/spring-cloud 。

### 小结

经过上述实例，我们成功的将Nacos集成到了Spring Cloud当中。相对来说，整个过程还是比较简单的，在实践时，大家唯一需要注意的就是版本问题。Spring Cloud的不同版本，内容和用法调整较大，多参考官方文档的说明。

