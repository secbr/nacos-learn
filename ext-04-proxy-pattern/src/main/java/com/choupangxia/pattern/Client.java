package com.choupangxia.pattern;

/**
 * @author sec
 * @version 1.0
 * @date 2021/7/26
 **/
public class Client {

    public static void main(String[] args) {

        CarService carService = new CarServiceProxy();
        carService.chooseCar();
        carService.qualityCheck();
    }
}
