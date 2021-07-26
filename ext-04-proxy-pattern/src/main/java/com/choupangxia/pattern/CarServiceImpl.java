package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/26
 **/
public class CarServiceImpl implements CarService {
    @Override
    public Car chooseCar() {
        System.out.println("真实操作：选车");
        return new Car();
    }

    @Override
    public boolean qualityCheck() {
        System.out.println("真实操作：质量检测");
        return true;
    }
}
