# Nacos中已经有Optional使用案例了，是时候慎重对待这一语法了

### 前言

Java 8提供了很多新特性，但很多朋友对此并不重视，依旧采用老的写法。最近个人在大量阅读开源框架的源码，发现Java 8的很多API已经被频繁的使用了。

以Nacos框架为例，已经有很典型的Optional使用案例了，而且场景把握的非常好。如果此时你还没意识到要学习了解一下，以后看源代码可能都有些费劲了。

今天这篇文章我们就基于Nacos中对Optional的使用作为案例，来深入讲解一下Optional的使用。老规矩，源码、案例、实战，一样不少。

### Nacos中的Optional使用

在Nacos中有这样一个接口ConsistencyService，用来定义一致性服务的，其中的一个方法返回的类型便是Optional：

```
/**
 * Get the error message of the consistency protocol.
 *
 * @return the consistency protocol error message.
 */
Optional<String> getErrorMsg();
```
如果你对Optional不了解，看到这里可能就会有点蒙。那我们来看看Nacos是怎么使用Optional的。在上述接口的一个实现类PersistentServiceProcessor中是如此实现的：

```
@Override
public Optional<String> getErrorMsg() {
    String errorMsg;
    if (hasLeader && hasError) {
        errorMsg = "The raft peer is in error: " + jRaftErrorMsg;
    } else if (hasLeader && !hasError) {
        errorMsg = null;
    } else if (!hasLeader && hasError) {
        errorMsg = "Could not find leader! And the raft peer is in error: " + jRaftErrorMsg;
    } else {
        errorMsg = "Could not find leader!";
    }
    return Optional.ofNullable(errorMsg);
}
```
也就是根据hasLeader和hasError两个变量来确定返回的errorMsg信息是什么。最后将errorMsg封装到Optional中进行返回。

下面再看看方法getErrorMsg是如何被调用的：

```
String errorMsg;
if (ephemeralConsistencyService.getErrorMsg().isPresent()
        && persistentConsistencyService.getErrorMsg().isPresent()) {
    errorMsg = "'" + ephemeralConsistencyService.getErrorMsg().get() + "' in Distro protocol and '"
            + persistentConsistencyService.getErrorMsg().get() + "' in jRaft protocol";
}
```
可以看到在使用时只用先调用返回的Optional的isPresent方法判断是否存在，再调用其get方法获取即可。此时你可以回想一下如果不用Optional该如何实现。

到此，你可能有所疑惑用法，没关系，下面我们就开始逐步讲解Option的使用、原理和源码。

### Optional的数据结构

面对新生事物我们都会有些许畏惧，当我们庖丁解牛似的将其拆分之后，了解其实现原理，就没那么恐怖了。

查看Optional类的源码，可以看到它有两个成员变量：

```
public final class Optional<T> {
    /**
     * Common instance for {@code empty()}.
     */
    private static final Optional<?> EMPTY = new Optional<>(null);

    /**
     * If non-null, the value; if null, indicates no value is present
     */
    private final T value;
    // ...
}
```
其中EMPTY变量表示的是如果创建一个空的Optional实例，很显然，在加载时已经初始化了。而value是用来存储我们业务中真正使用的对象，比如上面的errorMsg就是存储在这里。

看到这里你是否意识到Optional其实就一个容器啊！对的，将Optional理解为容器就对了，然后这个容器呢，为我们封装了存储对象的非空判断和获取的API。

看到这里，是不是感觉Optional并没那么神秘了？是不是也没那么恐惧了？

而Java 8之所以引入Optional也是为了解决对象使用时为避免空指针异常的丑陋写法问题。类似如下代码：
```
if( user != null){
    Address address = user.getAddress();
    if(address != null){
        String province = address.getProvince();
    }
}
```
原来是为了封装，原来是为了更优雅的代码，这不正是我们有志向的程序员所追求的么。

### 如何将对象存入Optional容器中

这么我们就姑且称Optional为Optional容器了，下面就看看如何将对象放入Optional当中。

看到上面的EMPTY初始化时调用了构造方法，传入null值，我们是否也可以这样来封装对象？好像不行，来看一下Optional的构造方法：
```
private Optional() {
    this.value = null;
}

private Optional(T value) {
    this.value = Objects.requireNonNull(value);
}
```
存在的两个构造方法都是private的，看来只能通过Optional提供的其他方法来封装对象了，通常有以下方式。

#### empty方法

empty方法源码如下：
```
public static<T> Optional<T> empty() {
    @SuppressWarnings("unchecked")
    Optional<T> t = (Optional<T>) EMPTY;
    return t;
}
```
简单直接，直接强转EMPTY对象。

#### of方法
of方法源码如下：
```
// Returns an {@code Optional} with the specified present non-null value.
public static <T> Optional<T> of(T value) {
    return new Optional<>(value);
}
```
注释上说是为非null的值创建一个Optional，而非null的是通过上面构造方法中的Objects.requireNonNull方法来检查的：

```
public static <T> T requireNonNull(T obj) {
    if (obj == null)
        throw new NullPointerException();
    return obj;
}
```
也就是说如果值为null，则直接抛空指针异常。

#### ofNullable方法

ofNullable方法源码如下：
```
public static <T> Optional<T> ofNullable(T value) {
    return value == null ? empty() : of(value);
}
```
ofNullable为指定的值创建一个Optional，如果指定的值为null，则返回一个空的Optional。也就是说此方法支持对象的null与非null构造。

回顾一下：Optional构造方法私有，不能被外部调用；empty方法创建空的Optional、of方法创建非空的Optional、ofNullable将两者结合。是不是so easy？

此时，有朋友可能会问，相对于ofNullable方法，of方法存在的意义是什么？在运行过程中，如果不想隐藏NullPointerException，就是说如果出现null则要立即报告，这时就用Of函数。另外就是已经明确知道value不会为null的时候也可以使用。

### 判断对象是否存在

上面已经将对象放入Optional了，那么在获取之前是否需要能判断一下存放的对象是否为null呢？

#### isPresent方法

上述问题，答案是：可以的。对应的方法就是isPresent：
```
public boolean isPresent() {
    return value != null;
}
```
实现简单直白，相当于将obj != null的判断进行了封装。该对象如果存在，方法返回true，否则返回false。

isPresent即判断value值是否为空，而ifPresent就是在value值不为空时，做一些操作：
```
public void ifPresent(Consumer<? super T> consumer) {
    if (value != null)
        consumer.accept(value);
}
```
如果Optional实例有值则为其调用consumer，否则不做处理。可以直接将Lambda表达式传递给该方法，代码更加简洁、直观。

```
Optional<String> opt = Optional.of("程序新视界");
opt.ifPresent(System.out::println);
```
### 获取值

当我们判断Optional中有值时便可以进行获取了，像Nacos中使用的那样，调用get方法：

```
public T get() {
    if (value == null) {
        throw new NoSuchElementException("No value present");
    }
    return value;
}
```
很显然，如果value值为null，则该方法会抛出NoSuchElementException异常。这也是为什么我们在使用时要先调用isPresent方法来判断一下value值是否存在了。此处的设计稍微与初衷相悖。

看一下使用示例：
```
String name = null;
Optional<String> opt = Optional.ofNullable(name);
if(opt.isPresent()){
	System.out.println(opt.get());
}
```

### 设置（或获取）默认值

那么，针对上述value为null的情况是否有解决方案呢？我们可以配合设置（或获取）默认值来解决。

#### orElse方法
orElse方法：如果有值则将其返回，否则返回指定的其它值。

```
public T orElse(T other) {
    return value != null ? value : other;
}
```
可以看到是get方法的加强版，get方法如果值为null直接抛异常，orElse则不，如果只为null，返回你传入进来的参数值。

使用示例：

```
Optional<Object> o1 = Optional.ofNullable(null);
// 输出orElse指定值
System.out.println(o1.orElse("程序新视界"));
```
#### orElseGet方法

orElseGet：orElseGet与orElse方法类似，区别在于得到的默认值。orElse方法将传入的对象作为默认值，orElseGet方法可以接受Supplier接口的实现用来生成默认值：

```
public T orElseGet(Supplier<? extends T> other) {
    return value != null ? value : other.get();
}
```
当value为null时orElse直接返回传入值，orElseGet返回Supplier实现类中定义的值。

```
String name = null;
String newName = Optional.ofNullable(name).orElseGet(()->"程序新视界");
System.out.println(newName); // 输出：程序新视界
```
其实上面的示例可以直接优化为orElse，因为Supplier接口的实现依旧是直接返回输入值。

#### orElseThrow方法

orElseThrow：如果有值则将其返回，否则抛出Supplier接口创建的异常。
```
public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
    if (value != null) {
        return value;
    } else {
        throw exceptionSupplier.get();
    }
}
```
使用示例：

```
Optional<Object> o = Optional.ofNullable(null);
try {
  o.orElseThrow(() -> new Exception("异常"));
} catch (Exception e) {
  System.out.println(e.getMessage());
}
```

学完上述内容，基本上已经掌握了Optional百分之八十的功能了。同时，还有两个相对高级点的功能：过滤值和转换值。

### filter方法过滤值

Optional中的值我们可以通过上面讲的到方法进行获取，但在某些场景下，我们还需要判断一下获得的值是否符合条件。笨办法时，获取值之后，自己再进行检查判断。

当然，也可以通过Optional提供的filter来进行取出前的过滤：

```
public Optional<T> filter(Predicate<? super T> predicate) {
    Objects.requireNonNull(predicate);
    if (!isPresent())
        return this;
    else
        return predicate.test(value) ? this : empty();
}
```
filter方法的参数类型为Predicate类型，可以将Lambda表达式传递给该方法作为条件，如果表达式的结果为false，则返回一个EMPTY的Optional对象，否则返回经过过滤的Optional对象。

使用示例：
```
Optional<String> opt = Optional.of("程序新视界");
Optional<String> afterFilter = opt.filter(name -> name.length() > 4);
System.out.println(afterFilter.orElse(""));
```
### map方法转换值

与filter方法类似，当我们将值从Optional中取出之后，还进行一步转换，比如改为大写或返回长度等操作。当然可以用笨办法取出之后，进行处理。

这里，Optional为我们提供了map方法，可以在取出之前就进行操作：

```
public<U> Optional<U> map(Function<? super T, ? extends U> mapper) {
    Objects.requireNonNull(mapper);
    if (!isPresent())
        return empty();
    else {
        return Optional.ofNullable(mapper.apply(value));
    }
}
```
map方法的参数类型为Function，会调用Function的apply方法对对Optional中的值进行处理。如果Optional中的值本身就为null，则返回空，否则返回处理过后的值。

示例：

```
Optional<String> opt = Optional.of("程序新视界");
Optional<Integer> intOpt = opt.map(String::length);
System.out.println(intOpt.orElse(0));
```

与map方法有这类似功能的方法为flatMap：

```
public<U> Optional<U> flatMap(Function<? super T, Optional<U>> mapper) {
    Objects.requireNonNull(mapper);
    if (!isPresent())
        return empty();
    else {
        return Objects.requireNonNull(mapper.apply(value));
    }
}
```
可以看出，它与map方法的实现非常像，不同的是传入的参数类型，map函数所接受的入参类型为Function<? super T, ? extends U>，而flapMap的入参类型为Function<? super T, Optional\<U\>>。

flapMap示例如下：
```
Optional<String> opt = Optional.of("程序新视界");
Optional<Integer> intOpt = opt.flatMap(name ->Optional.of(name.length()));
System.out.println(intOpt.orElse(0));
```
对照map的示例，可以看出在flatMap中对结果进行了一次Optional#of的操作。

### 小结

本文我们从Nacos中使用Optional的使用出发，逐步剖析了Optional的源码、原理和使用。此时再回头看最初的示例是不是已经豁然开朗了？

关于Optional的学习其实把握住本质就可以了：Optional本质上是一个对象的容器，将对象存入其中之后，可以帮我们做一些非空判断、取值、过滤、转换等操作。

理解了本质，如果哪个API的使用不确定，看一下源码就可以了。此时，可以愉快的继续看源码了~



