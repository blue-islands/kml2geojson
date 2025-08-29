package jp.livlog.kml2geojson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Kml2GeojsonCli {

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java -jar kml2geojson.jar <input.kml> <output.geojson|output.zip>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar kml2geojson.jar input.kml output.geojson   # 1ファイルにマージ");
        System.out.println("  java -jar kml2geojson.jar input.kml output.zip       # レイヤ毎に分割してZIP");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(1);
        }
        final String in  = args[0];
        final String out = args[1];

        final var exporter = new KmlLayersExporter();
        exporter.exportLayers(in, out); // 拡張子で自動切替

        final Path outPath = out.toLowerCase().endsWith(".zip") ? Paths.get(out)
                : out.toLowerCase().endsWith(".geojson") ? Paths.get(out)
                : Paths.get(out + ".zip");
        Files.exists(outPath);
        System.out.println("Done: " + outPath.toAbsolutePath());
    }
}
