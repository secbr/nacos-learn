package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/26
 **/
public class CarServiceProxy implements CarService {

    private CarServiceImpl real;

    public CarServiceProxy() {
        real = new CarServiceImpl();
    }

    @Override
    public Car chooseCar() {
        System.out.println("代理类CarServiceProxy选车：先添加一些日志");
        return real.chooseCar();
    }

    @Override
    public boolean qualityCheck() {
        System.out.println("代理类CarServiceProxy质量检测：先添加一些日志");
        return real.qualityCheck();
    }
}
