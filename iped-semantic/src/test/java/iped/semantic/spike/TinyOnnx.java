package iped.semantic.spike;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-encodes tiny ONNX models as protobuf bytes, so the dependency gate can exercise ONNX
 * Runtime and its extensions without shipping or downloading a model. Only the handful of
 * {@code onnx.proto} fields these models need are covered.
 */
final class TinyOnnx {

    static final int FLOAT = 1;
    static final int INT64 = 7;
    static final int STRING = 8;

    private TinyOnnx() {
    }

    /** {@code y = Relu(x)}, x and y float [N]: core operators only. */
    static byte[] relu() {
        byte[] node = node("Relu", "", new String[] { "x" }, new String[] { "y" });
        return model(graph(node, valueInfo("x", FLOAT), valueInfo("y", FLOAT)), false);
    }

    /** One node from the extensions' custom domain, string [N] in and out. */
    static byte[] contribStringOp(String opType) {
        byte[] node = node(opType, "ai.onnx.contrib", new String[] { "x" }, new String[] { "y" });
        return model(graph(node, valueInfo("x", STRING), valueInfo("y", STRING)), true);
    }

    private static byte[] model(byte[] graph, boolean contrib) {
        Proto m = new Proto();
        m.varint(1, 8); // ir_version
        m.string(2, "iped-semantic-spike"); // producer_name
        m.message(7, graph);
        m.message(8, opset("", 13));
        if (contrib) {
            m.message(8, opset("ai.onnx.contrib", 1));
        }
        return m.bytes();
    }

    private static byte[] opset(String domain, int version) {
        Proto p = new Proto();
        p.string(1, domain);
        p.varint(2, version);
        return p.bytes();
    }

    private static byte[] graph(byte[] node, byte[] input, byte[] output) {
        Proto g = new Proto();
        g.message(1, node);
        g.string(2, "g");
        g.message(11, input);
        g.message(12, output);
        return g.bytes();
    }

    private static byte[] node(String opType, String domain, String[] inputs, String[] outputs) {
        Proto n = new Proto();
        for (String in : inputs) {
            n.string(1, in);
        }
        for (String out : outputs) {
            n.string(2, out);
        }
        n.string(3, opType + "_0");
        n.string(4, opType);
        if (!domain.isEmpty()) {
            n.string(7, domain);
        }
        return n.bytes();
    }

    private static byte[] valueInfo(String name, int elemType) {
        Proto dim = new Proto();
        dim.string(2, "N"); // dim_param
        Proto shape = new Proto();
        shape.message(1, dim.bytes());
        Proto tensor = new Proto();
        tensor.varint(1, elemType);
        tensor.message(2, shape.bytes());
        Proto type = new Proto();
        type.message(1, tensor.bytes());
        Proto vi = new Proto();
        vi.string(1, name);
        vi.message(2, type.bytes());
        return vi.bytes();
    }

    /** Minimal protobuf writer: varint and length-delimited fields. */
    private static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void varint(int field, long value) {
            rawVarint((field << 3) | 0);
            rawVarint(value);
        }

        void string(int field, String value) {
            message(field, value.getBytes(StandardCharsets.UTF_8));
        }

        void message(int field, byte[] value) {
            rawVarint((field << 3) | 2);
            rawVarint(value.length);
            out.write(value, 0, value.length);
        }

        private void rawVarint(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
