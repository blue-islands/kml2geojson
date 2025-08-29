package jp.livlog.kml2geojson;

import java.io.File;

/** 引数不要。相対パス固定。data フォルダ等は事前に用意してください。 */
public class Kml2GeojsonCli {

    private static final String INPUT_KML  = "data/神奈川県水道道路.kml";

    private static final String OUTPUT_DIR = "data/out_layers";

    public static void main(final String[] args) throws Exception {

        final var inputFile = new File(Kml2GeojsonCli.INPUT_KML);
        final var outDir = new File(Kml2GeojsonCli.OUTPUT_DIR);

        if (!inputFile.isFile()) {
            System.err.println("入力KMLが見つかりません: " + inputFile.getPath());
            System.exit(1);
        }

        final var exporter = new KmlLayersExporter();
        final var count = exporter.exportLayers(inputFile, outDir);

        System.out.println("入力 : " + inputFile.getPath());
        System.out.println("出力 : " + outDir.getPath());
        System.out.println("レイヤ数: " + count);
    }
}
