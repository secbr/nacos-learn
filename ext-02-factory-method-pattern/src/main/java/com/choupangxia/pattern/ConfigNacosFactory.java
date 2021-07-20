package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/20
 **/
public class ConfigNacosFactory implements NacosFactory {

    @Override
    public NacosService getService() {
        return new ConfigService();
    }
}
