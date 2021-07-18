package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public class NacosFactory {

	public static NacosService getService(String name) {
		if ("naming".equals(name)) {
			return new NamingService();
		} else {
			return new ConfigService();
		}
	}

}
