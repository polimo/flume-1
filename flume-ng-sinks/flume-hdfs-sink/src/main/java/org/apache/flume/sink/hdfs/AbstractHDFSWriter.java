/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.sink.hdfs;

import com.google.common.base.Preconditions;
import org.apache.flume.Context;
import org.apache.flume.annotations.InterfaceAudience;
import org.apache.flume.annotations.InterfaceStability;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public abstract class AbstractHDFSWriter implements HDFSWriter {

  private static final Logger logger =
      LoggerFactory.getLogger(AbstractHDFSWriter.class);

  private FSDataOutputStream outputStream;
  private FileSystem fs;
  private Method refGetNumCurrentReplicas = null;
  private Integer configuredMinReplicas = null;

  final static Object [] NO_ARGS = new Object []{};

  @Override
  public void configure(Context context) {
    configuredMinReplicas = context.getInteger("hdfs.minBlockReplicas");
    if (configuredMinReplicas != null) {
      Preconditions.checkArgument(configuredMinReplicas >= 0,
          "hdfs.minBlockReplicas must be greater than or equal to 0");
    }
  }

  /**
   * Contract for subclasses: Call registerCurrentStream() on open,
   * unregisterCurrentStream() on close, and the base class takes care of the
   * rest.
   * @return
   */
  @Override
  public boolean isUnderReplicated() {
    try {
      int numBlocks = getNumCurrentReplicas();
      if (numBlocks == -1) {
        return false;
      }
      int desiredBlocks;
      if (configuredMinReplicas != null) {
        desiredBlocks = configuredMinReplicas;
      } else {
        desiredBlocks = getFsDesiredReplication();
      }
      return numBlocks < desiredBlocks;
    } catch (IllegalAccessException e) {
      logger.error("Unexpected error while checking replication factor", e);
    } catch (InvocationTargetException e) {
      logger.error("Unexpected error while checking replication factor", e);
    } catch (IllegalArgumentException e) {
      logger.error("Unexpected error while checking replication factor", e);
    }
    return false;
  }

  protected void registerCurrentStream(FSDataOutputStream outputStream,
                                      FileSystem fs) {
    Preconditions.checkNotNull(outputStream, "outputStream must not be null");
    Preconditions.checkNotNull(fs, "fs must not be null");

    this.outputStream = outputStream;
    this.fs = fs;
    this.refGetNumCurrentReplicas = reflectGetNumCurrentReplicas(outputStream);
  }

  protected void unregisterCurrentStream() {
    this.outputStream = null;
    this.fs = null;
    this.refGetNumCurrentReplicas = null;
  }

  public int getFsDesiredReplication() {
    if (fs != null) {
      return fs.getDefaultReplication();
    }
    return 0;
  }

  /**
   * This method gets the datanode replication count for the current open file.
   *
   * If the pipeline isn't started yet or is empty, you will get the default
   * replication factor.
   *
   * <p/>If this function returns -1, it means you
   * are not properly running with the HDFS-826 patch.
   * @throws InvocationTargetException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   */
  public int getNumCurrentReplicas()
      throws IllegalArgumentException, IllegalAccessException,
          InvocationTargetException {
    if (refGetNumCurrentReplicas != null && outputStream != null) {
      OutputStream dfsOutputStream = outputStream.getWrappedStream();
      if (dfsOutputStream != null) {
        Object repl = refGetNumCurrentReplicas.invoke(dfsOutputStream, NO_ARGS);
        if (repl instanceof Integer) {
          return ((Integer)repl).intValue();
        }
      }
    }
    return -1;
  }

  /**
   * Find the 'getNumCurrentReplicas' on the passed <code>os</code> stream.
   * @return Method or null.
   */
  private Method reflectGetNumCurrentReplicas(FSDataOutputStream os) {
    Method m = null;
    if (os != null) {
      Class<? extends OutputStream> wrappedStreamClass = os.getWrappedStream()
          .getClass();
      try {
        m = wrappedStreamClass.getDeclaredMethod("getNumCurrentReplicas",
            new Class<?>[] {});
        m.setAccessible(true);
      } catch (NoSuchMethodException e) {
        logger.info("FileSystem's output stream doesn't support"
            + " getNumCurrentReplicas; --HDFS-826 not available; fsOut="
            + wrappedStreamClass.getName() + "; err=" + e);
      } catch (SecurityException e) {
        logger.info("Doesn't have access to getNumCurrentReplicas on "
            + "FileSystems's output stream --HDFS-826 not available; fsOut="
            + wrappedStreamClass.getName(), e);
        m = null; // could happen on setAccessible()
      }
    }
    if (m != null) {
      logger.debug("Using getNumCurrentReplicas--HDFS-826");
    }
    return m;
  }

}
