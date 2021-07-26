package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/26
 **/
public interface CarService {

    /**
     * 选车
     */
    Car chooseCar();

    /**
     * 质量检查
     */
    boolean qualityCheck();
}
