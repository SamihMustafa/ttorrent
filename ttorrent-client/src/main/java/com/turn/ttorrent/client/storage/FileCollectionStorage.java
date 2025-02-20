/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.common.DummyLogger;
import com.turn.ttorrent.common.TorrentFile;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.TorrentMetadata;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;


/**
 * Multi-file torrent byte storage.
 *
 * <p>
 * This implementation of the torrent byte storage provides support for
 * multi-file torrents and completely abstracts the read/write operations from
 * the notion of different files. The byte storage is represented as one
 * continuous byte storage, directly accessible by offset regardless of which
 * file this offset lands.
 * </p>
 *
 * @author mpetazzoni
 * @author dgiffin
 */
public class FileCollectionStorage implements TorrentByteStorage {

  private static final DummyLogger logger =
          TorrentLoggerFactory.getLogger(FileCollectionStorage.class);

  private final List<FileStorage> files;
  private final long size;
  private volatile boolean myIsOpen;

  /**
   * Initialize a new multi-file torrent byte storage.
   *
   * @param files The list of individual {@link FileStorage}
   *              objects making up the torrent.
   * @param size  The total size of the torrent data, in bytes.
   */
  public FileCollectionStorage(List<FileStorage> files,
                               long size) {
    this.files = files;
    this.size = size;

    logger.debug("Initialized torrent byte storage on {} file(s) " +
            "({} total byte(s)).", files.size(), size);
  }

  public static FileCollectionStorage create(TorrentMetadata metadata, File parent) throws IOException {
    if (!parent.isDirectory()) {
      throw new IllegalArgumentException("Invalid parent directory!");
    }
    List<FileStorage> files = new LinkedList<FileStorage>();
    long offset = 0L;
    long totalSize = 0;
    for (TorrentFile file : metadata.getFiles()) {
      File actual = new File(parent, file.getRelativePathAsString());

      if (!actual.getCanonicalPath().startsWith(parent.getCanonicalPath())) {
        throw new SecurityException("Torrent file path attempted " +
                "to break directory jail!");
      }

      if (!actual.getParentFile().exists() && !actual.getParentFile().mkdirs()) {
        throw new IOException("Unable to create directories " + actual.getParent() + " for storing torrent file " + actual.getName());
      }
      files.add(new FileStorage(actual, offset, file.size));
      offset += file.size;
      totalSize += file.size;
    }
    return new FileCollectionStorage(files, totalSize);
  }

  public synchronized void open(final boolean seeder) throws IOException {
    for (FileStorage file : files) {
      if (!file.isOpen())
        file.open(seeder);
    }
    myIsOpen = true;
  }

  @Override
  public int read(ByteBuffer buffer, long position) throws IOException {
    int requested = buffer.remaining();
    int bytes = 0;

    for (FileOffset fo : this.select(position, requested)) {
      // TODO: remove cast to int when large ByteBuffer support is
      // implemented in Java.
      buffer.limit((int) (buffer.position() + fo.length));
      bytes += fo.file.read(buffer, fo.offset);
    }

    if (bytes < requested) {
      throw new IOException("Storage collection read underrun!");
    }

    return bytes;
  }

  @Override
  public int write(ByteBuffer buffer, long position) throws IOException {
    int requested = buffer.remaining();

    int bytes = 0;

    for (FileOffset fo : this.select(position, requested)) {
      buffer.limit(bytes + (int) fo.length);
      bytes += fo.file.write(buffer, fo.offset);
    }

    if (bytes < requested) {
      throw new IOException("Storage collection write underrun!");
    }

    return bytes;
  }

  @Override
  public boolean isBlank(long position, long size) {
    for (FileOffset fo : this.select(position, size)) {
      if (!fo.file.isBlank(fo.offset, fo.length)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean isBlank() {
    for (FileStorage file : this.files) {
      if (!file.isBlank()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public synchronized void close() throws IOException {
    for (FileStorage file : this.files) {
      file.close();
    }
    myIsOpen = false;
  }

  @Override
  public synchronized void finish() throws IOException {
    for (FileStorage file : this.files) {
      file.finish();
    }
  }

  @Override
  public boolean isFinished() {
    for (FileStorage file : this.files) {
      if (!file.isFinished()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public void delete() throws IOException {
    for (FileStorage file : files) {
      file.delete();
    }
  }

  /**
   * File operation details holder.
   *
   * <p>
   * This simple inner class holds the details for a read or write operation
   * on one of the underlying {@link FileStorage}s.
   * </p>
   *
   * @author dgiffin
   * @author mpetazzoni
   */
  private static class FileOffset {

    public final FileStorage file;
    public final long offset;
    public final long length;

    FileOffset(FileStorage file, long offset, long length) {
      this.file = file;
      this.offset = offset;
      this.length = length;
    }
  }

  /**
   * Select the group of files impacted by an operation.
   *
   * <p>
   * This function selects which files are impacted by a read or write
   * operation, with their respective relative offset and chunk length.
   * </p>
   *
   * @param offset The offset of the operation, in bytes, relative to the
   *               complete byte storage.
   * @param length The number of bytes to read or write.
   * @return A list of {@link FileOffset} objects representing the {@link
   * FileStorage}s impacted by the operation, bundled with their
   * respective relative offset and number of bytes to read or write.
   * @throws IllegalArgumentException If the offset and length go over the
   *                                  byte storage size.
   * @throws IllegalStateException    If the files registered with this byte
   *                                  storage can't accommodate the request (should not happen, really).
   */
  private List<FileOffset> select(long offset, long length) {
    if (offset + length > this.size) {
      throw new IllegalArgumentException("Buffer overrun (" +
              offset + " + " + length + " > " + this.size + ") !");
    }

    List<FileOffset> selected = new LinkedList<FileOffset>();
    long bytes = 0;

    for (FileStorage file : this.files) {
      if (file.offset() >= offset + length) {
        break;
      }

      if (file.offset() + file.size() < offset) {
        continue;
      }

      long position = offset - file.offset();
      position = position > 0 ? position : 0;
      long size = Math.min(
              file.size() - position,
              length - bytes);
      selected.add(new FileOffset(file, position, size));
      bytes += size;
    }

    if (selected.size() == 0 || bytes < length) {
      throw new IllegalStateException("Buffer underrun (only got " +
              bytes + " out of " + length + " byte(s) requested)!");
    }

    return selected;
  }
}
