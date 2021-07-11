# 学习Nacos？咱先把服务搞起来，实战教程

### 前言

前面已经写不少Nacos相关的文章了，比如《[Spring Cloud集成Nacos服务发现源码解析？翻了三套源码，保质保鲜！](https://mp.weixin.qq.com/s/JuzRf2E4AvdoQW4hrfJKVg)》，而且目前也计划写一个Spring Cloud的技术解析专栏，一个技术框架一个技术框架的为大家拆解分析原理和实现。

既然拿Nacos作为一个开始，那么我们这篇文章就来补充一下Nacos Server的部署以及Nacos Client的调用，直观的了解一下Nacos都包含了什么功能。这是使用Nacos的基础，也是后续进行深度剖析的依据。强烈建议一起学习一下。

### Nacos Server的部署

关于Nacos Server的部署，官方手册中已经进行了很详细的说明，对应链接地址（https://nacos.io/zh-cn/docs/deployment.html ）。其他方式的部署我们暂且不说，我们重点说明通过源码的形式进行构建和部署，这也是学习的最好方式。

Nacos部署的基本环境要求：JDK 1.8+，Maven 3.2.x+，准备好即可。

从Github上下载源码：

```
// 下载源码
git clone https://github.com/alibaba/nacos.git
// 进入源码目录
cd nacos/
// 执行编译打包操作
mvn -Prelease-nacos -Dmaven.test.skip=true clean install -U
// 查看生成的jar包
ls -al distribution/target/

// 进入到打包好的文件目录，后续可执行启动
cd distribution/target/nacos-server-$version/nacos/bin
```
经过上述命令进入到bin目录下，通常有不同环境的启动脚本：

```
shutdown.cmd	shutdown.sh	startup.cmd	startup.sh
```
执行对应环境的脚本即可进行启动，参数standalone代表着单机模式运行：
```
// Linux/Unix/Mac
sh startup.sh -m standalone
// ubuntu
bash startup.sh -m standalone
// Windows
startup.cmd -m standalone
```
上述操作适用于打包和部署，也适用于本地启动服务，但如果是学源码，则可以直接执行console(nacos-console)中的main方法（Nacos类）即可。

执行main方法启动，默认也是集群模式，可通过JVM参数来指定单机启动：

```
-Dnacos.standalone=true
```
如果为了方便，也可以直接在启动类的源码中直接添加该参数：

```
@SpringBootApplication(scanBasePackages = "com.alibaba.nacos")
@ServletComponentScan
@EnableScheduling
public class Nacos {

    public static void main(String[] args) {
        // 通过环境变量的形式设置单机启动
        System.setProperty(Constants.STANDALONE_MODE_PROPERTY_NAME, "true");

        SpringApplication.run(Nacos.class, args);
    }
}
```
经过上述步骤，我们已经可以启动一个Nacos Server了，后面就来看如何使用。

### Nacos管理后台

在启动Nacos Server时，控制台会打印如下日志信息：

```
         ,--.
       ,--.'|
   ,--,:  : |                                           Nacos 
,`--.'`|  ' :                       ,---.               Running in stand alone mode, All function modules
|   :  :  | |                      '   ,'\   .--.--.    Port: 8848
:   |   \ | :  ,--.--.     ,---.  /   /   | /  /    '   Pid: 47395
|   : '  '; | /       \   /     \.   ; ,. :|  :  /`./   Console: http://192.168.1.190:8848/nacos/index.html
'   ' ;.    ;.--.  .-. | /    / ''   | |: :|  :  ;_
|   | | \   | \__\/: . ..    ' / '   | .; : \  \    `.      https://nacos.io
'   : |  ; .' ," .--.; |'   ; :__|   :    |  `----.   \
|   | '`--'  /  /  ,.  |'   | '.'|\   \  /  /  /`--'  /
'   : |     ;  :   .'   \   :    : `----'  '--'.     /
;   |.'     |  ,     .-./\   \  /            `--'---'
'---'        `--`---'     `----'
```
通过上面的日志，可以看出启动的模式为“stand alone mode”，端口为8848，管理后台为：http://192.168.1.190:8848/nacos/index.html 。

这里我们直接访问本机服务：http://127.0.0.1:8848/nacos/index.html 。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-console-01-scaled.jpg)

默认情况下，用户和密码都是nacos。登录成功之后，大家可以随便点点，其中包含配置管理、服务管理、权限管理、命名空间、集群管理几个板块。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-console-02.jpg)

可以看到默认的命名空间为public，默认的用户为nacos。

此时执行一条curl命令，进行模拟服务注册：

```
curl -X POST 'http://127.0.0.1:8848/nacos/v1/ns/instance?serviceName=nacos.naming.serviceName&ip=20.18.7.10&port=8080'
```
执行之后，在此查看管理后台，会发现服务列表中已经添加了一条记录。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-console-03.jpg)

点击这条记录的详情，可以看到更多信息。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-console-04.jpg)

由于我们上面是通过一个命令注册的服务，这个服务并不存在，Nacos Server会定时检查服务的健康状态。你会发现，过了一会儿这个服务就不见了。这便是Nacos Server发现服务“挂掉”了，将其移除了。

![nacos](http://www.choupangxia.com/wp-content/uploads/2021/05/nacos-console-05.jpg)

其他的类似操作，大家可以尝试着通过curl命令或客户端工具进行尝试，同时配合管理后台看对应的数据。

服务发现命令：

```
curl -X GET 'http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=nacos.naming.serviceName'
```
发布配置命令：

```
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=nacos.cfg.dataId&group=test&content=HelloWorld"
```
获取配置命令：

```
curl -X GET "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=nacos.cfg.dataId&group=test"
```
### Spring Cloud集成Nacos

最后，我们再以一个简单的实例来讲Nacos集成到Spring Cloud项目当中，看是否能够将服务注册成功。关于Spring Cloud之间服务的调用，我们后面文章再专门讲解。

首先，新建一个Spring Boot项目，引入Spring Cloud和Spring Cloud Alibaba的依赖，完整的pom.xml文件如下：

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.4.2</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.springcloud</groupId>
    <artifactId>spring-cloud-alibaba-nacos</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>spring-cloud-alibaba-nacos</name>
    <description>springcloud alibaba nacos集成</description>
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
    <dependencies>
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
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <exclusions>
                <exclusion>
                    <groupId>org.junit.vintage</groupId>
                    <artifactId>junit-vintage-engine</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>

```
其中dependencyManagement中定义了Spring Cloud和Spring Cloud Alibaba的依赖的版本信息。这里需要注意的是Spring Cloud和Spring Boot的版本之间是有限制的。这个可以在https://spring.io/projects/spring-cloud中进行查看。

然后，在yml中配置nacos注册中心服务器地址：

```
spring:
  application:
    name: nacos-spring-cloud-learn
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```
此时，启动服务，查看Nacos Server的控制台，会发现，像前面直接执行curl命令的效果一样，成功的将服务注册到Nacos中了。

### 小结

本文通过给大家讲解如何部署Nacos服务、如何集成到SpringCloud，为后面更进一步学习SpringCloud做好准备。其中也涉及到一些实践经验和坑。








