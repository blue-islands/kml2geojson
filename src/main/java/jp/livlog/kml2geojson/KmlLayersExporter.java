package jp.livlog.kml2geojson;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * KML を Folder（レイヤ）単位で分割し、GeoJSON を出力するユーティリティ。
 * - <Folder> を1レイヤとして書き出し（入れ子は 親__子 形式）
 * - Folder 外の Placemark は root レイヤにまとめる
 * 依存: Jackson Databind
 */
public class KmlLayersExporter {

    private static final String GX_NS = "http://www.google.com/kml/ext/2.2";

    private final ObjectMapper  om    = new ObjectMapper();

    /** KML を読み込み、Folder（レイヤ）ごとに GeoJSON を outDir に出力します。 */
    public int exportLayers(final File kmlFile, File outDir) throws Exception {

        if (kmlFile == null || !kmlFile.isFile()) {
            throw new IllegalArgumentException("KMLファイルが無効です: " + kmlFile);
        }
        if (outDir == null) {
            outDir = new File("layers_" + KmlLayersExporter.stripExt(kmlFile.getName()));
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("出力ディレクトリを作成できません: " + outDir.getAbsolutePath());
        }

        final var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        final var doc = dbf.newDocumentBuilder().parse(kmlFile);

        final var folders = this.walkFolders(doc);

        final Map <Node, Boolean> inFolder = new IdentityHashMap <>();
        final var basePrefix = KmlLayersExporter.stripExt(kmlFile.getName());
        var fileCount = 0;

        // Folder 単位で出力
        for (final FolderRef fr : folders) {
            final var placemarks = KmlLayersExporter.directChildrenByLocalName(fr.folderEl, "Placemark");
            final List <ObjectNode> features = new ArrayList <>();
            for (final Element pm : placemarks) {
                final var feat = this.placemarkToFeature(pm);
                if (feat != null) {
                    features.add(feat);
                    inFolder.put(pm, Boolean.TRUE);
                }
            }
            if (!features.isEmpty()) {
                final var outName = basePrefix + "__" + KmlLayersExporter.sanitize(fr.pathName) + ".geojson";
                final var outFile = new File(outDir, outName);
                this.writeFeatureCollection(features, outFile);
                fileCount++;
            }
        }

        // Folder 外 → root に出力
        final var allPm = KmlLayersExporter.findAllByLocalName(doc.getDocumentElement(), "Placemark");
        final List <ObjectNode> rootFeatures = new ArrayList <>();
        for (final Element pm : allPm) {
            if (!inFolder.containsKey(pm)) {
                final var feat = this.placemarkToFeature(pm);
                if (feat != null) {
                    rootFeatures.add(feat);
                }
            }
        }
        if (!rootFeatures.isEmpty()) {
            final var outName = basePrefix + "__root.geojson";
            final var outFile = new File(outDir, outName);
            this.writeFeatureCollection(rootFeatures, outFile);
            fileCount++;
        }

        return fileCount;
    }

    // ===== GeoJSON helpers =====


    private void writeFeatureCollection(final List <ObjectNode> features, final File outFile) throws Exception {

        final var fc = this.om.createObjectNode();
        fc.put("type", "FeatureCollection");
        final var arr = this.om.createArrayNode();
        for (final ObjectNode f : features) {
            arr.add(f);
        }
        fc.set("features", arr);

        try (var fos = new FileOutputStream(outFile)) {
            fos.write(this.om.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(fc)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }


    private ObjectNode placemarkToFeature(final Element pm) {

        final var geom = this.parseGeometry(pm);
        if (geom == null) {
            return null;
        }

        final var props = this.om.createObjectNode();

        // ExtendedData (Data / SchemaData)
        for (final Element data : KmlLayersExporter.findAll(pm, e -> KmlLayersExporter.isLocal(e, "Data"))) {
            final var name = data.getAttribute("name");
            final var val = KmlLayersExporter.text(KmlLayersExporter.directChildByLocalName(data, "value"));
            if (name != null && !name.isBlank() && val != null) {
                props.put(name, val);
            }
        }
        for (final Element sdata : KmlLayersExporter.findAll(pm, e -> KmlLayersExporter.isLocal(e, "SimpleData"))) {
            final var name = sdata.getAttribute("name");
            final var val = KmlLayersExporter.text(sdata);
            if (name != null && !name.isBlank() && val != null && !props.has(name)) {
                props.put(name, val);
            }
        }

        final var name = KmlLayersExporter.text(KmlLayersExporter.directChildByLocalName(pm, "name"));
        final var desc = KmlLayersExporter.text(KmlLayersExporter.directChildByLocalName(pm, "description"));
        if (name != null) {
            props.put("name", name);
        }
        if (desc != null) {
            props.put("description", desc);
        }

        final var feat = this.om.createObjectNode();
        feat.put("type", "Feature");
        feat.set("geometry", geom);
        feat.set("properties", props);
        return feat;
    }


    private ObjectNode parseGeometry(final Element pm) {

        ObjectNode g;
        if (((g = this.parsePoint(pm)) != null) || ((g = this.parseLineString(pm)) != null) || ((g = this.parsePolygon(pm)) != null) || ((g = this.parseGxTrack(pm)) != null)) {
            return g;
        }
        if ((g = this.parseMultiGeometry(pm)) != null) {
            return g;
        }
        return null;
    }


    private ObjectNode parsePoint(final Element scope) {

        final var c = KmlLayersExporter.firstDescendant(scope, e -> KmlLayersExporter.isLocal(e, "Point"));
        if (c == null) {
            return null;
        }
        final var coordText = KmlLayersExporter.text(KmlLayersExporter.firstDescendant(c, e -> KmlLayersExporter.isLocal(e, "coordinates")));
        final var coords = KmlLayersExporter.parseCoords(coordText);
        if (coords.isEmpty()) {
            return null;
        }
        final var g = this.om.createObjectNode();
        g.put("type", "Point");
        g.set("coordinates", this.array(coords.get(0)));
        return g;
    }


    private ObjectNode parseLineString(final Element scope) {

        final var c = KmlLayersExporter.firstDescendant(scope, e -> KmlLayersExporter.isLocal(e, "LineString"));
        if (c == null) {
            return null;
        }
        final var coordText = KmlLayersExporter.text(KmlLayersExporter.firstDescendant(c, e -> KmlLayersExporter.isLocal(e, "coordinates")));
        final var coords = KmlLayersExporter.parseCoords(coordText);
        if (coords.size() < 2) {
            return null;
        }
        final var g = this.om.createObjectNode();
        g.put("type", "LineString");
        g.set("coordinates", this.array(coords));
        return g;
    }


    private ObjectNode parsePolygon(final Element scope) {

        final var poly = KmlLayersExporter.firstDescendant(scope, e -> KmlLayersExporter.isLocal(e, "Polygon"));
        if (poly == null) {
            return null;
        }

        var outerCoordsEl = KmlLayersExporter.firstDescendant(poly, e -> KmlLayersExporter.isLocal(e, "outerBoundaryIs"));
        if (outerCoordsEl != null) {
            outerCoordsEl = KmlLayersExporter.firstDescendant(outerCoordsEl, e -> KmlLayersExporter.isLocal(e, "coordinates"));
        }
        if (outerCoordsEl == null) {
            outerCoordsEl = KmlLayersExporter.firstDescendant(poly, e -> KmlLayersExporter.isLocal(e, "coordinates"));
        }
        final var oc = outerCoordsEl != null ? KmlLayersExporter.text(outerCoordsEl) : null;

        final var outer = KmlLayersExporter.parseCoords(oc);
        if (outer.isEmpty()) {
            return null;
        }
        if (!Arrays.equals(outer.get(0), outer.get(outer.size() - 1))) {
            outer.add(outer.get(0));
        }

        final List <List <double[]>> holes = new ArrayList <>();
        for (final Element inner : KmlLayersExporter.findAll(poly, e -> KmlLayersExporter.isLocal(e, "innerBoundaryIs"))) {
            final var txt = KmlLayersExporter.text(KmlLayersExporter.firstDescendant(inner, e -> KmlLayersExporter.isLocal(e, "coordinates")));
            final var ring = KmlLayersExporter.parseCoords(txt);
            if (!ring.isEmpty() && !Arrays.equals(ring.get(0), ring.get(ring.size() - 1))) {
                ring.add(ring.get(0));
            }
            if (!ring.isEmpty()) {
                holes.add(ring);
            }
        }

        final var g = this.om.createObjectNode();
        g.put("type", "Polygon");
        final var rings = this.om.createArrayNode();
        rings.add(this.array(outer));
        for (final List <double[]> h : holes) {
            rings.add(this.array(h));
        }
        g.set("coordinates", rings);
        return g;
    }


    private ObjectNode parseGxTrack(final Element scope) {

        final var track = KmlLayersExporter.firstDescendant(scope,
                e -> KmlLayersExporter.GX_NS.equals(e.getNamespaceURI()) && "Track".equals(e.getLocalName()));
        if (track == null) {
            return null;
        }

        final List <double[]> coords = new ArrayList <>();
        for (final Element ce : KmlLayersExporter.findAll(track,
                e -> KmlLayersExporter.GX_NS.equals(e.getNamespaceURI()) && "coord".equals(e.getLocalName()))) {
            final var s = KmlLayersExporter.text(ce);
            if (s == null) {
                continue;
            }
            final var sp = s.trim().split("\\s+");
            if (sp.length >= 2) {
                try {
                    final var lon = Double.parseDouble(sp[0]);
                    final var lat = Double.parseDouble(sp[1]);
                    coords.add(new double[] { lon, lat });
                } catch (final Exception ignore) {
                }
            }
        }
        if (coords.size() < 2) {
            return null;
        }

        final var g = this.om.createObjectNode();
        g.put("type", "LineString");
        g.set("coordinates", this.array(coords));
        return g;
    }


    private ObjectNode parseMultiGeometry(final Element scope) {

        final var mg = KmlLayersExporter.firstDescendant(scope, e -> KmlLayersExporter.isLocal(e, "MultiGeometry"));
        if (mg == null) {
            return null;
        }

        final var geoms = this.om.createArrayNode();
        for (final Element child : KmlLayersExporter.directChildrenElements(mg)) {
            ObjectNode g = null;
            if (KmlLayersExporter.isLocal(child, "Point")) {
                g = this.parsePoint(child);
            } else if (KmlLayersExporter.isLocal(child, "LineString")) {
                g = this.parseLineString(child);
            } else if (KmlLayersExporter.isLocal(child, "Polygon")) {
                g = this.parsePolygon(child);
            } else if (KmlLayersExporter.GX_NS.equals(child.getNamespaceURI()) && "Track".equals(child.getLocalName())) {
                g = this.parseGxTrack(child);
            }
            if (g != null) {
                geoms.add(g);
            }
        }
        if (geoms.isEmpty()) {
            return null;
        }

        final var gc = this.om.createObjectNode();
        gc.put("type", "GeometryCollection");
        gc.set("geometries", geoms);
        return gc;
    }

    // ===== XML helpers（Node で走査し、instanceof Element の時だけ使う） =====


    private static List <Element> directChildrenByLocalName(final Element parent, final String local) {

        final List <Element> out = new ArrayList <>();
        final var nl = parent.getChildNodes();
        for (var i = 0; i < nl.getLength(); i++) {
            final var n = nl.item(i);
            if (n instanceof final Element e && local.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }


    private static Element directChildByLocalName(final Element parent, final String local) {

        final var nl = parent.getChildNodes();
        for (var i = 0; i < nl.getLength(); i++) {
            final var n = nl.item(i);
            if (n instanceof final Element e && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }


    private static List <Element> directChildrenElements(final Element parent) {

        final List <Element> out = new ArrayList <>();
        final var nl = parent.getChildNodes();
        for (var i = 0; i < nl.getLength(); i++) {
            final var n = nl.item(i);
            if (n instanceof final Element e) {
                out.add(e);
            }
        }
        return out;
    }


    private static List <Element> findAllByLocalName(final Element root, final String local) {

        return KmlLayersExporter.findAll(root, e -> local.equals(e.getLocalName()));
    }


    /** 条件に合う全ての子孫要素を返す（安全版） */
    private static List <Element> findAll(final Element root, final Predicate <Element> pred) {

        final List <Element> out = new ArrayList <>();
        final Deque <Node> dq = new ArrayDeque <>();
        dq.add(root);

        while (!dq.isEmpty()) {
            final var cur = dq.removeFirst();

            if (cur instanceof final Element e) {
                if (pred.test(e)) {
                    out.add(e);
                }
            }

            final var nl = cur.getChildNodes();
            for (var i = 0; i < nl.getLength(); i++) {
                dq.addLast(nl.item(i)); // ここではキャストしない
            }
        }
        return out;
    }


    /** 条件に合う最初の子孫要素を返す（安全版） */
    private static Element firstDescendant(final Element root, final Predicate <Element> pred) {

        final Deque <Node> dq = new ArrayDeque <>();
        dq.add(root);

        while (!dq.isEmpty()) {
            final var cur = dq.removeFirst();

            if (cur instanceof final Element e) {
                if (pred.test(e)) {
                    return e;
                }
            }

            final var nl = cur.getChildNodes();
            for (var i = 0; i < nl.getLength(); i++) {
                dq.addLast(nl.item(i));
            }
        }
        return null;
    }

    // ===== utils =====


    private static boolean isLocal(final Element e, final String local) {

        return local.equals(e.getLocalName());
    }


    private static String text(final Node n) {

        return (n == null) ? null : (n.getTextContent() == null) ? null : n.getTextContent().trim();
    }


    private static String stripExt(final String name) {

        final var i = name.lastIndexOf('.');
        return (i >= 0) ? name.substring(0, i) : name;
    }


    private static String sanitize(final String name) {

        if (name == null) {
            return "unnamed";
        }
        final var s = name.trim().replace("/", "／").replace("\\", "＼")
                .replaceAll("[:*?\"<>|]", "_")
                .replaceAll("\\s+", " ");
        return s.isEmpty() ? "unnamed" : s;
    }

    // ===== coords / arrays =====


    private static List <double[]> parseCoords(final String coordText) {

        final List <double[]> out = new ArrayList <>();
        if (coordText == null || coordText.isBlank()) {
            return out;
        }
        final var parts = coordText.trim().split("\\s+");
        for (final String p : parts) {
            if (p.isBlank()) {
                continue;
            }
            final var nums = p.split(",");
            if (nums.length < 2) {
                continue;
            }
            try {
                final var lon = Double.parseDouble(nums[0]);
                final var lat = Double.parseDouble(nums[1]);
                out.add(new double[] { lon, lat });
            } catch (final Exception ignore) {
            }
        }
        return out;
    }


    private ArrayNode array(final double[] xy) {

        final var a = this.om.createArrayNode();
        a.add(xy[0]).add(xy[1]);
        return a;
    }


    private ArrayNode array(final List <double[]> line) {

        final var a = this.om.createArrayNode();
        for (final double[] p : line) {
            a.add(this.array(p));
        }
        return a;
    }

    // ===== Folder walk =====

    private static class FolderRef {

        final String  pathName;

        final Element folderEl;

        FolderRef(final String pathName, final Element folderEl) {

            this.pathName = pathName;
            this.folderEl = folderEl;
        }
    }

    private List <FolderRef> walkFolders(final Document doc) {

        final List <FolderRef> result = new ArrayList <>();
        for (final Element d : KmlLayersExporter.findAll(doc.getDocumentElement(), e -> KmlLayersExporter.isLocal(e, "Document"))) {
            for (final Element f : KmlLayersExporter.directChildrenByLocalName(d, "Folder")) {
                this.dfsFolder(f, new ArrayList <>(), result);
            }
        }
        final var root = doc.getDocumentElement();
        for (final Element f : KmlLayersExporter.directChildrenByLocalName(root, "Folder")) {
            this.dfsFolder(f, new ArrayList <>(), result);
        }
        return result;
    }


    private void dfsFolder(final Element folder, final List <String> path, final List <FolderRef> out) {

        var name = KmlLayersExporter.text(KmlLayersExporter.directChildByLocalName(folder, "name"));
        if (name == null || name.isBlank()) {
            name = "unnamed";
        }
        final List <String> newPath = new ArrayList <>(path);
        newPath.add(name);
        final var pathName = String.join("__", newPath);
        out.add(new FolderRef(pathName, folder));

        for (final Element sub : KmlLayersExporter.directChildrenByLocalName(folder, "Folder")) {
            this.dfsFolder(sub, newPath, out);
        }
    }
}
