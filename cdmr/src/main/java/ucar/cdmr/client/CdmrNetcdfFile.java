/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.cdmr.client;

import com.google.common.base.Stopwatch;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import ucar.cdmr.CdmRemoteGrpc;
import ucar.cdmr.CdmRemoteProto.DataRequest;
import ucar.cdmr.CdmRemoteProto.DataResponse;
import ucar.cdmr.CdmRemoteProto.Header;
import ucar.cdmr.CdmRemoteProto.HeaderRequest;
import ucar.cdmr.CdmRemoteProto.HeaderResponse;
import ucar.cdmr.CdmrConverter;
import ucar.ma2.Array;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Section;
import ucar.ma2.StructureDataIterator;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.ParsedSectionSpec;
import ucar.nc2.Structure;
import ucar.nc2.Variable;

/** A remote CDM dataset, using cdmremote protocol to communicate. */
public class CdmrNetcdfFile extends NetcdfFile {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CdmrNetcdfFile.class);
  private static final int MAX_MESSAGE = 51 * 1000 * 1000; // 51 Mb

  public static final String PROTOCOL = "cdmr";
  public static final String SCHEME = PROTOCOL + ":";

  public static void setDebugFlags(ucar.nc2.util.DebugFlags debugFlag) {
    showRequest = debugFlag.isSet("CdmRemote/showRequest");
  }

  private static boolean showRequest = true;

  @Override
  public Array readSection(String variableSection) throws IOException {
    if (showRequest)
      System.out.printf("CdmrNetcdfFile data request forspec=(%s)%n url='%s'%n path='%s'%n", variableSection,
          this.remoteURI, this.path);
    final Stopwatch stopwatch = Stopwatch.createStarted();

    List<Array> results = new ArrayList<>();
    long size = 0;
    DataRequest request = DataRequest.newBuilder().setLocation(this.path).setVariableSpec(variableSection).build();
    try {
      Iterator<DataResponse> responses = blockingStub.withDeadlineAfter(15, TimeUnit.SECONDS).getData(request);
      while (responses.hasNext()) {
        DataResponse response = responses.next();
        if (response.hasError()) {
          throw new IOException(response.getError().getMessage());
        }
        Array result;
        Section sectionReturned = CdmrConverter.decodeSection(response.getSection());
        if (response.getIsVariableLength()) {
          result = CdmrConverter.decodeVlenData(response.getData(), sectionReturned);
        } else {
          result = CdmrConverter.decodeData(response.getData(), sectionReturned);
        }
        results.add(result);
        size += result.getSize();
      }

    } catch (StatusRuntimeException e) {
      log.warn("readSection requestData failed failed: ", e);
      throw new IOException(e);

    } catch (Throwable t) {
      System.out.printf(" ** failed after %s%n", stopwatch);
      log.warn("readSection requestData failed failed: ", t);
      throw new IOException(t);
    }
    System.out.printf(" ** size=%d took=%s%n", size, stopwatch.stop());

    // LOOK;
    if (results.size() == 1) {
      return results.get(0);
    } else {
      return combine(variableSection, results);
    }
  }

  private Array combine(String variableSection, List<Array> results) {
    ParsedSectionSpec spec;
    try {
      spec = ParsedSectionSpec.parseVariableSection(this, variableSection);
    } catch (InvalidRangeException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

    Section section = spec.getSection();
    Variable var = spec.getVariable();
    long size = section.getSize();
    if (size > Integer.MAX_VALUE) {
      throw new OutOfMemoryError();
    }

    switch (var.getDataType()) {
      case FLOAT: {
        float[] all = new float[(int) size];
        int start = 0;
        for (Array result : results) {
          float[] array = (float[]) result.getStorage();
          System.arraycopy(array, 0, all, start, array.length);
          start += array.length;
        }
        return Array.factory(var.getDataType(), section.getShape(), all);
      }
      case DOUBLE: {
        double[] all = new double[(int) size];
        int start = 0;
        for (Array result : results) {
          double[] array = (double[]) result.getStorage();
          System.arraycopy(array, 0, all, start, array.length);
          start += array.length;
        }
        return Array.factory(var.getDataType(), section.getShape(), all);
      }
      default:
        throw new RuntimeException(" DataType " + var.getDataType());
    }

  }

  @Override
  protected Array readData(Variable v, Section sectionWanted) throws IOException {
    String variableSection = ParsedSectionSpec.makeSectionSpecString(v, sectionWanted.getRanges());
    return readSection(variableSection);
  }

  @Override
  protected StructureDataIterator getStructureIterator(Structure s, int bufferSize) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getFileTypeId() {
    return PROTOCOL;
  }

  @Override
  public String getFileTypeDescription() {
    return PROTOCOL;
  }

  @Override
  public synchronized void close() {
    try {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interruptedException) {
      log.warn("CdmrNetcdfFile shutdown interrupted");
      // fall through
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////

  private final String remoteURI;
  private final String path;
  private final ManagedChannel channel;
  private final CdmRemoteGrpc.CdmRemoteBlockingStub blockingStub;

  private CdmrNetcdfFile(Builder<?> builder) {
    super(builder);
    this.remoteURI = builder.remoteURI;
    this.path = builder.path;
    this.channel = builder.channel;
    this.blockingStub = builder.blockingStub;
  }

  public Builder<?> toBuilder() {
    return addLocalFieldsToBuilder(builder());
  }

  private Builder<?> addLocalFieldsToBuilder(Builder<? extends Builder<?>> b) {
    b.setRemoteURI(this.remoteURI);
    return (Builder<?>) super.addLocalFieldsToBuilder(b);
  }

  /**
   * Get Builder for this class that allows subclassing.
   *
   * @see "https://community.oracle.com/blogs/emcmanus/2010/10/24/using-builder-pattern-subclasses"
   */
  public static Builder<?> builder() {
    return new Builder2();
  }

  private static class Builder2 extends Builder<Builder2> {
    @Override
    protected Builder2 self() {
      return this;
    }
  }

  public static abstract class Builder<T extends Builder<T>> extends NetcdfFile.Builder<T> {
    private String remoteURI;
    private ManagedChannel channel;
    private CdmRemoteGrpc.CdmRemoteBlockingStub blockingStub;
    private String path;
    private boolean built;

    protected abstract T self();

    public T setRemoteURI(String remoteURI) {
      this.remoteURI = remoteURI;
      return self();
    }

    public CdmrNetcdfFile build() {
      if (built)
        throw new IllegalStateException("already built");
      built = true;
      openChannel();
      return new CdmrNetcdfFile(this);
    }

    private void openChannel() {
      URI uri = java.net.URI.create(this.remoteURI);

      String target = uri.getAuthority();
      this.path = uri.getPath();
      if (this.path.startsWith("/")) {
        this.path = this.path.substring(1);
      }

      // Create a communication channel to the server, known as a Channel. Channels are thread-safe
      // and reusable. It is common to create channels at the beginning of your application and reuse
      // them until the application shuts down.
      this.channel = ManagedChannelBuilder.forTarget(target)
          // Channels are secure by default (via SSL/TLS). For now, we disable TLS to avoid needing certificates.
          .usePlaintext() //
          .enableFullStreamDecompression() //
          .maxInboundMessageSize(MAX_MESSAGE) //
          .build();
      try {
        this.blockingStub = CdmRemoteGrpc.newBlockingStub(channel);
        readHeader(path);

      } catch (Exception e) {
        // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
        // resources the channel should be shut down when it will no longer be used. If it may be used
        // again leave it running.
        try {
          channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
          log.warn("Shutdown interrupted ", e);
          // fall through
        }
        e.printStackTrace();
        throw new RuntimeException("Cant open CdmRemote url " + this.remoteURI, e);
      }
    }

    private void readHeader(String location) {
      log.info("CdmrNetcdfFile request header for " + location);
      HeaderRequest request = HeaderRequest.newBuilder().setLocation(location).build();
      HeaderResponse response = blockingStub.getHeader(request);
      if (response.hasError()) {
        throw new RuntimeException(response.getError().getMessage());
      } else {
        Header header = response.getHeader();
        setId(header.getId());
        setTitle(header.getTitle());
        setLocation(SCHEME + header.getLocation());

        this.rootGroup = Group.builder().setName("");
        CdmrConverter.decodeGroup(header.getRoot(), this.rootGroup);
      }
    }

  }

}
