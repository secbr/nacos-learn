package com.choupangxia.pattern;

/**
 * 抽象产品类
 * @author sec
 * @version 1.0
 * @date 2021/7/18
 **/
public interface NacosService {


	/**
	 * 注册实例信息
	 * @param object 实例信息，这里用Object代替
	 */
	void register(Object object);
}
