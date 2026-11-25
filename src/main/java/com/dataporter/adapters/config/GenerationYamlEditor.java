package com.dataporter.adapters.config;

import com.dataporter.generation.ports.out.GenerationConfigEditor.CollectionChanges;
import com.dataporter.shared.error.ConfigurationException;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.StringReader;
import java.util.*;

/** Edits YAML source spans so active rules, scalar spelling, and user comments can be left verbatim. */
final class GenerationYamlEditor {
    private static final String MISSING_START = "# DataPorterGen missing fields";
    private static final String MISSING_END = "# DataPorterGen end missing fields";
    private final String source;
    private final String newline;
    private final String bom;
    private final int[] unicodeOffsets;
    private final List<Edit> edits = new ArrayList<>();

    GenerationYamlEditor(String yaml) {
        bom = yaml.startsWith("\uFEFF") ? "\uFEFF" : "";
        source = yaml.substring(bom.length());
        newline = source.contains("\r\n") ? "\r\n" : "\n";
        unicodeOffsets = source.length() == source.codePointCount(0, source.length())
                ? null : source.codePoints().map(Character::charCount).toArray();
        if (unicodeOffsets != null) {
            int offset = 0;
            for (int i = 0; i < unicodeOffsets.length; i++) {
                int width = unicodeOffsets[i];
                unicodeOffsets[i] = offset;
                offset += width;
            }
        }
    }

    String edit(List<CollectionChanges> changes) {
        try {
            LoaderOptions options = new LoaderOptions();
            MappingNode root = (MappingNode) new Yaml(options).compose(new StringReader(source));
            SequenceNode collections = (SequenceNode) property(root, "collections");
            for (int i = 0; i < changes.size(); i++)
                editCollection((MappingNode) collections.getValue().get(i), collections.getFlowStyle() == FlowStyle.FLOW,
                        changes.get(i));
        } catch (YAMLException | ClassCastException e) {
            throw new ConfigurationException("Cannot locate editable generation config fields in YAML", e);
        }
        StringBuilder updated = new StringBuilder(source);
        edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).reversed());
        int lastStart = source.length();
        for (Edit edit : edits) {
            if (edit.end() > lastStart) throw new ConfigurationException("Overlapping generation config edits");
            updated.replace(edit.start(), edit.end(), edit.text());
            lastStart = edit.start();
        }
        return bom + updated;
    }

    private void editCollection(MappingNode collection, boolean flowCollectionList, CollectionChanges changes) {
        MappingNode fields = (MappingNode) property(collection, "fields");
        List<String> archived = new ArrayList<>();
        if (!changes.obsolete().isEmpty()) {
            if (fields.getFlowStyle() == FlowStyle.FLOW) editFlowFields(fields, changes.obsolete(), archived);
            else commentBlockFields(fields, changes.obsolete());
        }
        int start = start(collection);
        int line = lineStart(start);
        String prefix = source.substring(line, start);
        boolean inline = flowCollectionList && !prefix.isBlank();
        String indent = inline ? "  " : " ".repeat(prefix.indexOf('-') >= 0 ? prefix.indexOf('-') : prefix.length());
        StringBuilder header = new StringBuilder();
        if (!archived.isEmpty()) {
            header.append(indent).append("# DataPorterGen fields absent from source").append(newline);
            archived.forEach(text -> header.append(comment(text, indent)).append(newline));
        }
        if (!changes.missing().isEmpty()) {
            String collectionName = ((ScalarNode) property(collection, "name")).getValue();
            header.append(indent).append(MISSING_START).append(newline);
            changes.missing().forEach((path, types) -> {
                List<String> sortedTypes = types.stream().sorted().toList();
                header.append(indent).append("# ").append(commentPath(path)).append(": ")
                        .append(String.join(" | ", sortedTypes)).append("  # example: ")
                        .append(BsonFieldExample.render(collectionName, path, sortedTypes)).append(newline);
            });
            header.append(indent).append(MISSING_END).append(newline);
        }
        if (inline && !header.isEmpty()) edits.add(new Edit(start, start, newline + header + indent));
        else if (!inline) {
            int managedStart = managedHeaderStart(line, indent);
            if (managedStart != line || !header.isEmpty()) edits.add(new Edit(managedStart, line, header.toString()));
        }
    }

    private void commentBlockFields(MappingNode fields, Set<String> obsolete) {
        var entries = fields.getValue();
        for (NodeTuple entry : entries) {
            if (!obsolete.contains(key(entry))) continue;
            int keyStart = start(entry.getKeyNode());
            int first = lineStart(keyStart);
            String prefix = source.substring(first, keyStart);
            if (!prefix.isBlank() && !prefix.strip().equals("?"))
                throw new ConfigurationException("Cannot comment out a generation field on the collection header line");
            int last = end(entry.getValueNode());
            if (last > first && source.charAt(last - 1) != '\n') last = lineEnd(last);
            String indent = " ".repeat(start(entries.getFirst().getKeyNode()) - lineStart(start(entries.getFirst().getKeyNode())));
            String text = comment(source.substring(first, last), indent);
            if (entry == entries.getFirst() && obsolete.size() == entries.size())
                text = indent + "{}" + newline + text;
            edits.add(new Edit(first, last, text));
        }
    }

    private void editFlowFields(MappingNode fields, Set<String> obsolete, List<String> archived) {
        List<NodeTuple> entries = fields.getValue();
        int remaining = entries.size() - obsolete.size();
        int close = offset(fields.getEndMark().getIndex()) - 1;
        for (int i = 0; i < entries.size(); i++) {
            NodeTuple entry = entries.get(i);
            int first = start(entry.getKeyNode());
            int last = end(entry.getValueNode());
            if (obsolete.contains(key(entry))) {
                archived.add(source.substring(first, last));
                edits.add(new Edit(first, last, ""));
            } else if (--remaining > 0) continue;
            int limit = i + 1 < entries.size() ? start(entries.get(i + 1).getKeyNode()) : close;
            int comma = findComma(last, limit);
            if (comma >= 0) edits.add(new Edit(comma, comma + 1, ""));
        }
    }

    private int findComma(int start, int limit) {
        for (int offset = start; offset < limit; offset++) {
            char ch = source.charAt(offset);
            if (ch == ',') return offset;
            if (ch == '#') offset = lineEnd(offset);
        }
        return -1;
    }

    private int managedHeaderStart(int line, String indent) {
        if (line == 0) return line;
        int previous = lineStart(line - 1);
        if (!source.substring(previous, lineEnd(previous)).equals(indent + MISSING_END)) return line;
        while (previous > 0) {
            previous = lineStart(previous - 1);
            String text = source.substring(previous, lineEnd(previous));
            if (text.equals(indent + MISSING_START)) return previous;
            if (!text.startsWith(indent + "# /")) break;
        }
        return line;
    }

    private String comment(String text, String indent) {
        return String.join(newline, text.lines().map(line -> {
            int leading = Math.min(indent.length(), line.length() - line.stripLeading().length());
            return indent + "# " + line.substring(leading);
        }).toList()) + (text.endsWith("\n") ? newline : "");
    }

    private static String commentPath(String path) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(path))
                .replace("\u0085", "\\u0085").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static Node property(MappingNode node, String name) {
        return node.getValue().stream().filter(tuple -> name.equals(key(tuple)))
                .map(NodeTuple::getValueNode).findFirst()
                .orElseThrow(() -> new ConfigurationException("Cannot locate generation config property " + name));
    }

    private static String key(NodeTuple tuple) { return ((ScalarNode) tuple.getKeyNode()).getValue(); }
    private int start(Node node) { return offset(node.getStartMark().getIndex()); }

    private int end(Node node) {
        if (node instanceof MappingNode map && map.getFlowStyle() == FlowStyle.BLOCK && !map.getValue().isEmpty())
            return end(map.getValue().getLast().getValueNode());
        if (node instanceof SequenceNode sequence && sequence.getFlowStyle() == FlowStyle.BLOCK && !sequence.getValue().isEmpty())
            return end(sequence.getValue().getLast());
        return offset(node.getEndMark().getIndex());
    }

    private int offset(int index) {
        return unicodeOffsets == null ? index : index == unicodeOffsets.length ? source.length() : unicodeOffsets[index];
    }
    private int lineStart(int offset) { return source.lastIndexOf('\n', offset - 1) + 1; }
    private int lineEnd(int offset) {
        int end = source.indexOf('\n', offset);
        if (end < 0) return source.length();
        return end > 0 && source.charAt(end - 1) == '\r' ? end - 1 : end;
    }

    private record Edit(int start, int end, String text) {}
}
