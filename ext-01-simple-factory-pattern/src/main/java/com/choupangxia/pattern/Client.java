package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public class Client {

	public static void main(String[] args) {

		NacosService nacosService = NacosFactory.getService("naming");
		nacosService.register(new Object());
	}
}
