package com.choupangxia.pattern;

/**
 * 命名服务注册实现
 *
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public class NamingService implements NacosService {

	@Override
	public void register(Object object) {
		System.out.println("注册命名服务成功");
	}
}
