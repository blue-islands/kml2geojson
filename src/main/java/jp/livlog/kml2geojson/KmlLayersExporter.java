package jp.livlog.kml2geojson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * KML を GeoJSON に変換するエクスポータ。
 * - 入力: byte[]（KML）
 * - 出力: レイヤ（Folder）毎の GeoJSON を ZIP に格納した byte[] または単一 GeoJSON の byte[]
 *
 * 公開API:
 *   - exportLayers(byte[])                      : レイヤ別ZIPを返す
 *   - exportLayers(byte[], String)              : ZIP内ファイル名の接頭辞を指定
 *   - exportMergedGeoJson(byte[])               : 全Placemarkを1つにマージした単一GeoJSONのbyte[]
 *   - exportLayers(String inputKml, String out) : CLI/ライブラリ用（拡張子で自動出力）
 */
public class KmlLayersExporter {

    // ================== 公開API（パス版・CLI/ライブラリ向け） ==================

    /**
     * 入力KMLパス → 出力パスへ書き出し。
     * 出力拡張子が .geojson の場合: 単一GeoJSONにマージしたものを出力。
     * 上記以外: レイヤ別に分割したZIP（.zip でない場合は自動で .zip を付与）。
     */
    public void exportLayers(final String inputKmlPath, final String outputPath) throws Exception {
        final Path in = Paths.get(inputKmlPath);
        if (!Files.isRegularFile(in)) {
            throw new IllegalArgumentException("KMLが見つかりません: " + in.toAbsolutePath());
        }
        final byte[] kmlBytes = Files.readAllBytes(in);
        final String lower = outputPath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".geojson")) {
            final byte[] merged = this.exportMergedGeoJson(kmlBytes);
            final Path out = Paths.get(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, merged);
        } else {
            final byte[] zip = this.exportLayers(kmlBytes);
            Path out = Paths.get(outputPath);
            if (!lower.endsWith(".zip")) {
                out = Paths.get(outputPath + ".zip");
            }
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, zip);
        }
    }

    // ================== 公開API（byte[]版） ==================

    /**
     * KML(byte[]) → レイヤ（Folder）毎に分割したGeoJSONをZIPにまとめて返す（byte[]）。
     * ZIP内の各ファイル名は "<basePrefix>__<folderPath>.geojson"。
     * basePrefix は <Document><name> があればそれをサニタイズして使用、無ければ "layers"。
     */
    public byte[] exportLayers(final byte[] kmlBytes) throws Exception {
        return exportLayers(kmlBytes, null);
    }

    /**
     * KML(byte[]) → レイヤ（Folder）毎に分割したGeoJSONをZIPにまとめて返す（byte[]）。
     * basePrefixOverride を指定すると ZIP 内の各ファイル名の接頭辞に使用される。
     */
    public byte[] exportLayers(final byte[] kmlBytes, final String basePrefixOverride) throws Exception {
        if (kmlBytes == null || kmlBytes.length == 0) {
            throw new IllegalArgumentException("KMLバイト配列が空です");
        }
        final Document doc = parseKml(kmlBytes);
        final Element kmlRoot = doc.getDocumentElement();
        final Element docEl = firstDescendantByLocalName(kmlRoot, "Document");
        final String basePrefix = sanitize(
                (basePrefixOverride != null && !basePrefixOverride.isBlank())
                        ? basePrefixOverride.trim()
                        : (docEl != null ? orDefault(text(directChildByLocalName(docEl, "name")), "layers") : "layers")
        );

        final List<FolderRef> folders = walkFolders(doc);
        final Map<Node, Boolean> inFolder = new IdentityHashMap<>();

        try (var baos = new ByteArrayOutputStream();
             var zos  = new ZipOutputStream(baos)) {

            // Folder毎に直接の Placemark を収集して出力
            for (final FolderRef fr : folders) {
                final List<Element> placemarks = directChildrenByLocalName(fr.folderEl, "Placemark");
                final List<ObjectNode> features = new ArrayList<>();
                for (final Element pm : placemarks) {
                    final ObjectNode f = placemarkToFeature(pm, fr.pathName);
                    if (f != null) {
                        features.add(f);
                        inFolder.put(pm, Boolean.TRUE);
                    }
                }
                if (!features.isEmpty()) {
                    final String entry = basePrefix + "__" + sanitize(fr.pathName) + ".geojson";
                    final byte[] json = featureCollectionBytes(features);
                    zos.putNextEntry(new ZipEntry(entry));
                    zos.write(json);
                    zos.closeEntry();
                }
            }

            // どのFolderにも属さない Placemark を root にまとめる
            final List<Element> allPm = findAllByLocalName(kmlRoot, "Placemark");
            final List<ObjectNode> rootFeatures = new ArrayList<>();
            for (final Element pm : allPm) {
                if (!inFolder.containsKey(pm)) {
                    final ObjectNode f = placemarkToFeature(pm, "");
                    if (f != null) rootFeatures.add(f);
                }
            }
            if (!rootFeatures.isEmpty()) {
                final String entry = basePrefix + "__root.geojson";
                final byte[] json = featureCollectionBytes(rootFeatures);
                zos.putNextEntry(new ZipEntry(entry));
                zos.write(json);
                zos.closeEntry();
            }

            zos.finish();
            return baos.toByteArray();
        }
    }

    /**
     * KML(byte[]) → すべての Placemark を 1 つの FeatureCollection にまとめた単一GeoJSONの byte[] を返す。
     */
    public byte[] exportMergedGeoJson(final byte[] kmlBytes) throws Exception {
        if (kmlBytes == null || kmlBytes.length == 0) {
            throw new IllegalArgumentException("KMLバイト配列が空です");
        }
        final Document doc = parseKml(kmlBytes);
        final Element kmlRoot = doc.getDocumentElement();

        final List<Element> allPm = findAllByLocalName(kmlRoot, "Placemark");
        final List<ObjectNode> features = new ArrayList<>();
        for (final Element pm : allPm) {
            final ObjectNode f = placemarkToFeature(pm, folderPathOf(pm));
            if (f != null) features.add(f);
        }
        return featureCollectionBytes(features);
    }

    // ================== 内部実装 ==================

    private final ObjectMapper om = new ObjectMapper();

    // ---- KMLパース ----
    private static Document parseKml(final byte[] kmlBytes) throws Exception {
        final var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(kmlBytes));
    }

    // ---- Folder探索（パス名生成つき） ----

    /** Folderとその"親からのパス名"を保持する参照 */
    private static final class FolderRef {
        final Element folderEl;
        final String pathName;

        FolderRef(final Element folderEl, final String pathName) {
            this.folderEl = folderEl;
            this.pathName = pathName;
        }
    }

    /** Document配下の全Folderを、親子のnameを "_" で連結したpathName付きで列挙 */
    private static List<FolderRef> walkFolders(final Document doc) {
        final Element root = doc.getDocumentElement();
        final Element document = firstDescendantByLocalName(root, "Document");
        final Element start = (document != null) ? document : root;

        final List<FolderRef> out = new ArrayList<>();
        final Deque<String> path = new ArrayDeque<>();
        walkFoldersRecursive(start, path, out);
        return out;
    }

    private static void walkFoldersRecursive(final Element parent, final Deque<String> path, final List<FolderRef> out) {
        final List<Element> children = directChildrenByLocalName(parent, "Folder");
        for (final Element f : children) {
            final String nm = orDefault(text(directChildByLocalName(f, "name")), "unnamed");
            path.addLast(nm);
            out.add(new FolderRef(f, String.join("_", path)));
            // ネストしたFolderも探索
            walkFoldersRecursive(f, path, out);
            path.removeLast();
        }
    }

    // ---- Placemark → Feature 変換 ----

    /** Placemark を GeoJSON Feature に変換（folderPath は properties に入れる） */
    private ObjectNode placemarkToFeature(final Element placemark, final String folderPath) {
        final Element geom = findGeometry(placemark);
        if (geom == null) return null;

        final ObjectNode geometry = toGeoJsonGeometry(geom);
        if (geometry == null) return null;

        final ObjectNode feat = om.createObjectNode();
        feat.put("type", "Feature");
        feat.set("geometry", geometry);

        final ObjectNode props = om.createObjectNode();
        final String name = text(directChildByLocalName(placemark, "name"));
        if (name != null && !name.isBlank()) props.put("name", name);

        final String desc = text(directChildByLocalName(placemark, "description"));
        if (desc != null && !desc.isBlank()) props.put("description", desc);

        final String styleUrl = text(directChildByLocalName(placemark, "styleUrl"));
        if (styleUrl != null && !styleUrl.isBlank()) props.put("styleUrl", styleUrl);

        // ExtendedData(Data/SchemaData) を properties に取り込み
        addExtendedDataProps(placemark, props);

        if (folderPath != null && !folderPath.isBlank()) {
            props.put("folderPath", folderPath);
        }

        feat.set("properties", props);
        return feat;
    }

    /** Placemark 直下の最初の Geometry (<Point|LineString|Polygon|MultiGeometry>) を探す */
    private static Element findGeometry(final Element placemark) {
        final String[] types = new String[]{"Point", "LineString", "Polygon", "MultiGeometry"};
        for (String t : types) {
            final Element el = firstDescendantByLocalName(placemark, t);
            if (el != null) return el;
        }
        return null;
    }

    /** KMLジオメトリ → GeoJSON geometry */
    private ObjectNode toGeoJsonGeometry(final Element geometry) {
        final String ln = localName(geometry);
        switch (ln) {
            case "Point":
                return pointToGeoJson(geometry);
            case "LineString":
                return lineStringToGeoJson(geometry);
            case "Polygon":
                return polygonToGeoJson(geometry);
            case "MultiGeometry":
                return multiGeometryToGeoJson(geometry);
            default:
                return null;
        }
    }

    private ObjectNode pointToGeoJson(final Element point) {
        final Element coordsEl = firstDescendantByLocalName(point, "coordinates");
        if (coordsEl == null) return null;
        final List<double[]> coords = parseCoordinates(text(coordsEl));
        if (coords.isEmpty()) return null;
        final ObjectNode g = om.createObjectNode();
        g.put("type", "Point");
        g.set("coordinates", toPositionArray(coords.get(0)));
        return g;
    }

    private ObjectNode lineStringToGeoJson(final Element ls) {
        final Element coordsEl = firstDescendantByLocalName(ls, "coordinates");
        if (coordsEl == null) return null;
        final List<double[]> coords = parseCoordinates(text(coordsEl));
        if (coords.size() < 2) return null;
        final ObjectNode g = om.createObjectNode();
        g.put("type", "LineString");
        final ArrayNode arr = om.createArrayNode();
        for (double[] p : coords) arr.add(toPositionArray(p));
        g.set("coordinates", arr);
        return g;
    }

    private ObjectNode polygonToGeoJson(final Element poly) {
        final ObjectNode g = om.createObjectNode();
        g.put("type", "Polygon");
        final ArrayNode rings = om.createArrayNode();

        // outerBoundaryIs / LinearRing
        final Element outer = firstDescendantByLocalName(poly, "outerBoundaryIs");
        if (outer != null) {
            final Element lr = firstDescendantByLocalName(outer, "LinearRing");
            if (lr != null) {
                final Element coordsEl = firstDescendantByLocalName(lr, "coordinates");
                final List<double[]> coords = (coordsEl != null) ? parseCoordinates(text(coordsEl)) : List.of();
                if (!coords.isEmpty()) {
                    final ArrayNode ring = om.createArrayNode();
                    for (double[] p : coords) ring.add(toPositionArray(p));
                    ensureClosedLinearRing(ring);
                    rings.add(ring);
                }
            }
        }

        // innerBoundaryIs / LinearRing (holes)
        final List<Element> inners = descendantsByLocalName(poly, "innerBoundaryIs");
        for (final Element ib : inners) {
            final Element lr = firstDescendantByLocalName(ib, "LinearRing");
            if (lr == null) continue;
            final Element coordsEl = firstDescendantByLocalName(lr, "coordinates");
            if (coordsEl == null) continue;
            final List<double[]> coords = parseCoordinates(text(coordsEl));
            if (coords.isEmpty()) continue;
            final ArrayNode ring = om.createArrayNode();
            for (double[] p : coords) ring.add(toPositionArray(p));
            ensureClosedLinearRing(ring);
            rings.add(ring);
        }

        if (rings.isEmpty()) return null;
        g.set("coordinates", rings);
        return g;
    }

    private ObjectNode multiGeometryToGeoJson(final Element mg) {
        final ObjectNode g = om.createObjectNode();
        g.put("type", "GeometryCollection");
        final ArrayNode geoms = om.createArrayNode();

        // 子の幾何をすべて変換して geometrycollection に格納
        final String[] types = new String[]{"Point", "LineString", "Polygon", "MultiGeometry"};
        for (String t : types) {
            final List<Element> els = descendantsByLocalName(mg, t);
            for (Element el : els) {
                final ObjectNode child = toGeoJsonGeometry(el);
                if (child != null) geoms.add(child);
            }
        }

        if (geoms.isEmpty()) return null;
        g.set("geometries", geoms);
        return g;
    }

    // ---- ExtendedData を properties へ ----

    private void addExtendedDataProps(final Element placemark, final ObjectNode props) {
        final Element ext = firstDescendantByLocalName(placemark, "ExtendedData");
        if (ext == null) return;

        // <Data name=""><value>v</value></Data>
        final List<Element> datas = descendantsByLocalName(ext, "Data");
        for (Element d : datas) {
            final String name = attr(d, "name");
            if (name == null || name.isBlank()) continue;
            final String value = text(directChildByLocalName(d, "value"));
            if (value != null) props.put(name, value);
        }

        // <SchemaData> <SimpleData name="">v</SimpleData> ...
        final List<Element> schemaDatas = descendantsByLocalName(ext, "SchemaData");
        for (Element sd : schemaDatas) {
            final List<Element> simples = descendantsByLocalName(sd, "SimpleData");
            for (Element s : simples) {
                final String name = attr(s, "name");
                if (name == null || name.isBlank()) continue;
                final String value = text(s);
                if (value != null) props.put(name, value);
            }
        }
    }

    // ---- GeoJSON FeatureCollection のシリアライズ ----

    /** 与えられたFeature配列からGeoJSON文字列のUTF-8バイト列を作成 */
    private byte[] featureCollectionBytes(final List<ObjectNode> features) throws Exception {
        final ObjectNode fc = om.createObjectNode();
        fc.put("type", "FeatureCollection");
        final ArrayNode arr = om.createArrayNode();
        for (final ObjectNode f : features) arr.add(f);
        fc.set("features", arr);
        final String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(fc);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // ---- ユーティリティ ----

    private static String folderPathOf(final Element placemark) {
        // 祖先のFolderのnameをたどって "_" で連結
        final Deque<String> names = new ArrayDeque<>();
        Node cur = placemark.getParentNode();
        while (cur != null && cur.getNodeType() == Node.ELEMENT_NODE) {
            final Element el = (Element) cur;
            if ("Folder".equals(localName(el))) {
                final String nm = orDefault(text(directChildByLocalName(el, "name")), "unnamed");
                names.addFirst(nm);
            }
            cur = cur.getParentNode();
        }
        if (names.isEmpty()) return "";
        return String.join("_", names);
    }

    private static String localName(final Element el) {
        final String ln = el.getLocalName();
        if (ln != null) return ln;
        final String nn = el.getNodeName();
        final int idx = nn.indexOf(':');
        return (idx >= 0) ? nn.substring(idx + 1) : nn;
    }

    private static Element directChildByLocalName(final Element parent, final String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            final Element el = (Element) n;
            if (Objects.equals(localName(el), localName)) return el;
        }
        return null;
    }

    private static List<Element> directChildrenByLocalName(final Element parent, final String localName) {
        final List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            final Element el = (Element) n;
            if (Objects.equals(localName(el), localName)) out.add(el);
        }
        return out;
    }

    private static Element firstDescendantByLocalName(final Element root, final String localName) {
        final NodeList nl = root.getElementsByTagNameNS("*", localName);
        if (nl.getLength() == 0) return null;
        return (Element) nl.item(0);
    }

    private static List<Element> descendantsByLocalName(final Element root, final String localName) {
        final NodeList nl = root.getElementsByTagNameNS("*", localName);
        final List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static List<Element> findAllByLocalName(final Element root, final String localName) {
        return descendantsByLocalName(root, localName);
    }

    private static String text(final Element el) {
        if (el == null) return null;
        final String t = el.getTextContent();
        if (t == null) return null;
        final String s = t.trim();
        return s.isEmpty() ? null : s;
    }

    private static String attr(final Element el, final String name) {
        if (el == null) return null;
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    private static String orDefault(final String v, final String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]+");

    /** ファイル名に使えない文字を安全化 */
    private static String sanitize(final String s) {
        String out = s;
        out = out.replace('\u0000', ' ');
        out = ILLEGAL.matcher(out).replaceAll("_");
        out = out.replaceAll("\\s+", "_");
        return out;
    }

    // ---- coordinates パース ----

    /**
     * KML の coordinates テキストを [ [lon,lat(,alt)] ... ] にパース。
     * 区切り: 空白/改行/タブ/カンマ。
     * 小数点は常に '.' を想定（KML仕様）。
     */
    private static List<double[]> parseCoordinates(final String coordText) {
        final List<double[]> out = new ArrayList<>();
        if (coordText == null || coordText.isBlank()) return out;

        // KMLは "lon,lat,alt lon,lat,alt ..." のような形式（改行 or 空白区切り）
        final StringTokenizer tok = new StringTokenizer(coordText, " \r\n\t");
        while (tok.hasMoreTokens()) {
            String triple = tok.nextToken().trim();
            if (triple.isEmpty()) continue;
            // まれに連続カンマが混入することがあるため、空要素はスキップ
            String[] parts = triple.split(",");
            if (parts.length < 2) continue;

            double lon = parseDoubleSafe(parts[0]);
            double lat = parseDoubleSafe(parts[1]);

            if (parts.length >= 3 && !parts[2].isBlank()) {
                double alt = parseDoubleSafe(parts[2]);
                out.add(new double[]{lon, lat, alt});
            } else {
                out.add(new double[]{lon, lat});
            }
        }
        return out;
    }

    private static double parseDoubleSafe(final String s) {
        try {
            // KMLはピリオド小数点だが、念のためロケールに依存しないよう変換
            final char ds = DecimalFormatSymbols.getInstance(Locale.US).getDecimalSeparator(); // '.'
            String norm = s.trim().replace(',', ','); // no-op, 明示
            norm = norm.replace('\u3000', ' ');
            return Double.parseDouble(norm);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** [lon,lat,(alt?)] 配列を ArrayNode に変換 */
    private ArrayNode toPositionArray(final double[] p) {
        final ArrayNode arr = om.createArrayNode();
        // lon, lat, (opt) alt
        arr.add(p[0]);
        arr.add(p[1]);
        if (p.length >= 3 && !Double.isNaN(p[2])) arr.add(p[2]);
        return arr;
    }

    /** LinearRing が閉じていなければ最初の点を末尾に複製して閉じる */
    private static void ensureClosedLinearRing(final ArrayNode ring) {
        if (ring == null || ring.size() < 3) return;
        final var first = ring.get(0);
        final var last  = ring.get(ring.size() - 1);
        if (!first.equals(last)) {
            ring.add(first.deepCopy());
        }
    }
}
