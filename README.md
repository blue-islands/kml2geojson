# kml2geojson

KML ファイルを GeoJSON に変換するための Java ライブラリです。  
コマンドラインツールとして利用できるほか、ライブラリとしてアプリケーションに組み込むこともできます。

## 特徴

- Google Earth / Google マイマップ などで出力した **KML ファイルを GeoJSON 形式に変換**
- フォルダ・レイヤー構造を保持して出力
- Java 17 で動作確認済み

## インストール（JitPack）

[![](https://jitpack.io/v/blue-islands/kml2geojson.svg)](https://jitpack.io/#blue-islands/kml2geojson)

本ライブラリは [JitPack](https://jitpack.io) を通じて公開されています。  
Maven/Gradle プロジェクトに以下を追加してください。

### Maven
```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.blue-islands</groupId>
  <artifactId>kml2geojson</artifactId>
  <version>0.0.1</version>
</dependency>
````

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.blue-islands:kml2geojson:0.0.1")
}
````

## 使い方

### コマンドラインツールとして

```bash
java -jar kml2geojson.jar input.kml output.geojson
```

### ライブラリとして利用

```java
import jp.livlog.kml2geojson.KmlLayersExporter;

public class Example {
    public static void main(String[] args) throws Exception {
        KmlLayersExporter exporter = new KmlLayersExporter();
        exporter.exportLayers("input.kml", "output.geojson");
    }
}
```

## ビルド方法

```bash
git clone https://github.com/blue-islands/kml2geojson.git
cd kml2geojson
mvn clean install
```

ビルド後、`target/kml2geojson-0.0.1.jar` が生成されます。

## 開発環境

* Java 17
* Maven 3.9+
* JUnit 5

## ライセンス

このプロジェクトは MIT ライセンスの下で公開されています。
