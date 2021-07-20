package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public class Client {

    public static void main(String[] args) {

        // 配置中心
        ConfigNacosFactory configNacosFactory = new ConfigNacosFactory();
        NacosService configService = configNacosFactory.getService();
        configService.register(new Object());

        // 注册中心
        NamingNacosFactory namingNacosFactory = new NamingNacosFactory();
        NacosService namingService = namingNacosFactory.getService();
        namingService.register(new Object());
    }
}
