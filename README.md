在根目录下的**build.gradle.kts** 加入下面的库地址以及插件依赖

```

repositories {
  mavenCentral()
}

dependencies {
    classpath("cn.entertech.android:publish:1.0.4")
}
```

在需要打包的library｜gradle插件目录下的  **build.gradle.kts** 文件添加下面的插件

```
plugins {
    id("custom.android.plugin")
}
```

配置库相关信息：在需要打包的library｜gradle插件 目录下的  **build.gradle.kts** 文件添加下面的插件

```
PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "base"
    version = "0.0.1"
    implementationClass=""
    publishUrl = ""

    publishUserName= ""
    publishPassword: String = ""
}
```
groupId，artifactId，version必填项
implementationClass属性是在打gradle插件需要的属性
publishUrl 上传的库地址
publishUserName 上传的maven 库 账号
publishPassword 上传的maven 库 密码

根目录下的local.properties文件，配置的是整个工程的，优先度比PublishInfo属性要低，**==设置的值不能用引号引起来==**

```
publishUrl=https://com.1223.com/dfjka
publishUserName=fjdakjf
publishPassword=dfajkjkj
```

