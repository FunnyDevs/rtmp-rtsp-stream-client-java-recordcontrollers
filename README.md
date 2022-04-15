# rtmp-rtsp-stream-client-java-recordcontrollers


Some RecordControllers to be used with https://github.com/pedroSG94/rtmp-rtsp-stream-client-java

## XugglerReccordController

It can record mp4 video using Xuggler library (based on FFMPEG).

To use it

```gradle
allprojects {
  repositories {
    maven {
      url 'https://dl.cloudsmith.io/public/olivier-ayache/xuggler/maven/'
    }
  }
}
dependencies {
  implementation 'xuggle:xuggle-xuggler-android-all:5.7.0-SNAPSHOT'
}

```


## FlvReccordController

As the name suggests, it is used to record FLV videos.

To use it

```gradle
allprojects {
  repositories {
    jcenter()
  }
}
dependencies {
  implementation 'com.laifeng:sopcast-sdk:1.0.4'
}

```


Thanks
------
* **pedroSG94** for his RTSP-RTMP library
* **olivierayache** for mantaining Xuggler (https://github.com/olivierayache/xuggle-xuggler) and for his help in using Xuggler 
* **LaiFeng-Android** for his library (https://github.com/LaiFeng-Android/SopCastComponent), used to write FlvRecordController 



