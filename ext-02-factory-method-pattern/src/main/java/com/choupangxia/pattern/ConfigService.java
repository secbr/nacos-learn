package com.choupangxia.pattern;

/**
 * 配置中心服务实现
 *
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public class ConfigService implements NacosService {

	@Override
	public void register(Object object) {
		System.out.println("配置中心实例注册成功");
	}
}
