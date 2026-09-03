package com.dataporter.adapters.snapshot;

import com.dataporter.generation.domain.CollectionGenerationSpec;
import com.dataporter.generation.ports.out.GenerationSource;
import com.dataporter.generation.ports.out.TemplateCatalog;
import com.dataporter.generation.ports.out.TemplateCatalogFactory;
import com.dataporter.shared.bson.BsonPayload;
import com.dataporter.shared.bson.DataBatch;
import com.dataporter.shared.error.SourceInspectionException;
import com.dataporter.shared.ports.out.BatchCursor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileTemplateCatalogFactory implements TemplateCatalogFactory {
    private static final Logger log = LoggerFactory.getLogger(FileTemplateCatalogFactory.class);
    private static final int COPY_BATCH_SIZE = 1000;
    private static final long PROGRESS_TEMPLATES = 100_000;

    @Override public TemplateCatalog snapshot(GenerationSource source, List<CollectionGenerationSpec> collections, long maxBytes) {
        Path directory = null;
        try {
            if (collections.isEmpty()) throw new SourceInspectionException("Template snapshot requires at least one collection");
            long collectionBytes = usableBytes(maxBytes) / collections.size();
            directory = Files.createTempDirectory("dataporter-generation-");
            restrict(directory);
            Map<String, Entry> entries = new LinkedHashMap<>();
            int number = 0;
            for (CollectionGenerationSpec collection : collections) {
                Path data = directory.resolve("templates-" + number + ".bson");
                Path index = directory.resolve("templates-" + number + ".idx");
                restrict(Files.createFile(data)); restrict(Files.createFile(index));
                long count = 0, bytes = 0, offset = 0;
                boolean truncated = false;
                log.info("Template snapshot reading collection={} queryApplied={}",
                        collection.name(), !collection.query().isMatchAll());
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(data)));
                     DataOutputStream offsets = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(index)));
                     BatchCursor cursor = source.openTemplateBatches(
                             collection.name(), collection.query(), COPY_BATCH_SIZE)) {
                    snapshot:
                    for (DataBatch batch; (batch = cursor.next()) != null; ) {
                        for (BsonPayload payload : batch.documents()) {
                            long recordBytes = Integer.BYTES + Long.BYTES + (long) payload.size();
                            if (recordBytes > collectionBytes - bytes) {
                                truncated = true;
                                break snapshot;
                            }
                            offsets.writeLong(offset);
                            output.writeInt(payload.size());
                            output.write(payload.bytes());
                            offset += Integer.BYTES + (long) payload.size();
                            bytes += recordBytes;
                            count++;
                            if (count % PROGRESS_TEMPLATES == 0)
                                log.info("Template snapshot progress collection={} templates={} bytes={}",
                                        collection.name(), count, bytes);
                        }
                    }
                }
                log.info("Template snapshot completed collection={} templates={} bytes={} truncated={}",
                        collection.name(), count, bytes, truncated);
                if (count == 0) {
                    if (truncated) throw new SourceInspectionException(
                            "Source collection first template does not fit its maxWorkingMegabytes snapshot share: " + collection.name());
                    if (!collection.query().isMatchAll()) throw new SourceInspectionException(
                            "Source collection query matched no template documents: " + collection.name());
                    throw new SourceInspectionException("Source collection has no template documents: " + collection.name());
                }
                entries.put(collection.name(), new Entry(data, index, count, bytes, truncated));
                number++;
            }
            return new FileTemplateCatalog(directory, entries);
        } catch (RuntimeException | IOException e) {
            if (directory != null) {
                try { deleteTree(directory); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            if (e instanceof SourceInspectionException inspection) throw inspection;
            throw new SourceInspectionException("Cannot create raw BSON template snapshot", e);
        }
    }

    private static long usableBytes(long maxBytes) {
        if (maxBytes < 1) throw new SourceInspectionException("maxWorkingMegabytes snapshot limit must be positive");
        long reservedBytes = 1 + (maxBytes - 1) / 100;
        return maxBytes - reservedBytes;
    }

    private record Entry(Path data, Path index, long count, long bytes, boolean truncated) { }

    private static final class FileTemplateCatalog implements TemplateCatalog {
        private final Path directory;
        private final Map<String, Entry> entries;
        // Channels stay open for the catalog lifetime: positional reads are thread-safe and avoid
        // two file opens per generated document. Closed before deleteTree so Windows can remove files.
        private final Map<String, Channels> channels;
        private volatile boolean closed;
        private FileTemplateCatalog(Path directory, Map<String, Entry> entries) {
            this.directory = directory; this.entries = Map.copyOf(entries);
            Map<String, Channels> opened = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, Entry> item : this.entries.entrySet())
                    opened.put(item.getKey(), new Channels(FileChannel.open(item.getValue().index(), StandardOpenOption.READ),
                            FileChannel.open(item.getValue().data(), StandardOpenOption.READ)));
            } catch (IOException e) {
                opened.values().forEach(Channels::closeQuietly);
                throw new SourceInspectionException("Cannot open template snapshot", e);
            }
            this.channels = opened;
        }
        public long count(String collection) { return entry(collection).count; }
        public long bytes(String collection) { return entry(collection).bytes; }
        public boolean truncated(String collection) { return entry(collection).truncated; }
        public BsonPayload get(String collection, long ordinal) {
            Entry entry = entry(collection);
            if (ordinal < 0 || ordinal >= entry.count) throw new IndexOutOfBoundsException("template ordinal " + ordinal);
            Channels pair = channels.get(collection);
            try {
                ByteBuffer offsetBytes = ByteBuffer.allocate(Long.BYTES);
                readFully(pair.offsets(), offsetBytes, ordinal * Long.BYTES);
                long offset = offsetBytes.flip().getLong();
                ByteBuffer lengthBytes = ByteBuffer.allocate(Integer.BYTES);
                readFully(pair.data(), lengthBytes, offset);
                int length = lengthBytes.flip().getInt();
                if (length < 5 || length > 16 * 1024 * 1024) throw new IOException("invalid BSON record length");
                ByteBuffer payload = ByteBuffer.allocate(length);
                readFully(pair.data(), payload, offset + Integer.BYTES);
                return new BsonPayload(payload.array());
            } catch (IOException e) { throw new SourceInspectionException("Cannot read template snapshot for " + collection, e); }
        }
        private Entry entry(String collection) {
            if (closed) throw new IllegalStateException("template catalog is closed");
            Entry entry = entries.get(collection);
            if (entry == null) throw new IllegalArgumentException("unknown template collection " + collection);
            return entry;
        }
        public void close() {
            if (closed) return;
            closed = true;
            channels.values().forEach(Channels::closeQuietly);
            deleteTree(directory);
        }
    }

    private record Channels(FileChannel offsets, FileChannel data) {
        void closeQuietly() {
            try { offsets.close(); } catch (IOException ignored) { }
            try { data.close(); } catch (IOException ignored) { }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) throw new EOFException("unexpected end of snapshot");
            position += read;
        }
    }
    static void restrict(Path path) {
        Set<PosixFilePermission> ownerOnly = Files.isDirectory(path)
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(ownerOnly);
                if (!posix.readAttributes().permissions().equals(ownerOnly))
                    throw new SourceInspectionException("Cannot secure template snapshot permissions for " + path);
                return;
            }
            if (!restrictOwnerOnlyAcl(path))
                throw new SourceInspectionException("Cannot secure template snapshot permissions for " + path);
        } catch (IOException e) {
            throw new SourceInspectionException("Cannot secure template snapshot permissions for " + path, e);
        }
    }

    /** Windows/other fallback: drop every non-owner ALLOW entry, then verify the applied ACL. */
    private static boolean restrictOwnerOnlyAcl(Path path) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) return false;
            UserPrincipal owner = Files.getOwner(path);
            AclEntry ownerEntry = null;
            for (AclEntry entry : view.getAcl())
                if (entry.type() == AclEntryType.ALLOW && owner.equals(entry.principal())) ownerEntry = entry;
            if (ownerEntry == null || ownerEntry.permissions().isEmpty()) return false;
            view.setAcl(List.of(ownerEntry));
            for (AclEntry entry : view.getAcl())
                if (entry.type() == AclEntryType.ALLOW && !owner.equals(entry.principal())) return false;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void deleteTree(Path directory) {
        List<Path> failures = new ArrayList<>();
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException e) { failures.add(path); }
            });
        } catch (IOException e) {
            throw new SourceInspectionException("Template snapshot cleanup failed for " + directory, e);
        }
        if (!failures.isEmpty())
            throw new SourceInspectionException("Template snapshot cleanup failed for " + directory
                    + ": " + failures.size() + " file(s) remain");
    }
}
