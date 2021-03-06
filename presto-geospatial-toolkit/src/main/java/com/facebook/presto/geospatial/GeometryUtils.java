/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.geospatial;

import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.GeometryCursor;
import com.esri.core.geometry.GeometryMemorySizeUtilsPackageWorkaround;
import com.esri.core.geometry.ogc.OGCConcreteGeometryCollection;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.google.common.base.Verify;
import io.airlift.slice.BasicSliceInput;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.esri.core.geometry.Geometry.Type.Unknown;
import static com.esri.core.geometry.GeometryEngine.geometryToEsriShape;
import static com.esri.core.geometry.OperatorImportFromESRIShape.local;
import static com.esri.core.geometry.ogc.OGCGeometry.createFromEsriGeometry;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.Collections.emptyList;

public final class GeometryUtils
{
    private static final int POINT_TYPE = 1;

    public enum GeometryTypeName
    {
        POINT(0),
        MULTI_POINT(1),
        LINE_STRING(2),
        MULTI_LINE_STRING(3),
        POLYGON(4),
        MULTI_POLYGON(5),
        GEOMETRY_COLLECTION(6);

        private final int code;

        GeometryTypeName(int code)
        {
            this.code = code;
        }

        public int code()
        {
            return code;
        }
    }

    public static final String POINT = "Point";
    public static final String LINE_STRING = "LineString";
    public static final String POLYGON = "Polygon";
    public static final String MULTI_POINT = "MultiPoint";
    public static final String MULTI_LINE_STRING = "MultiLineString";
    public static final String MULTI_POLYGON = "MultiPolygon";
    public static final String GEOMETRY_COLLECTION = "GeometryCollection";
    public static final int SPATIAL_REFERENCE_UNKNOWN = 0;

    private GeometryUtils() {}

    public static GeometryTypeName valueOf(String type)
    {
        switch (type) {
            case POINT:
                return GeometryTypeName.POINT;
            case MULTI_POINT:
                return GeometryTypeName.MULTI_POINT;
            case LINE_STRING:
                return GeometryTypeName.LINE_STRING;
            case MULTI_LINE_STRING:
                return GeometryTypeName.MULTI_LINE_STRING;
            case POLYGON:
                return GeometryTypeName.POLYGON;
            case MULTI_POLYGON:
                return GeometryTypeName.MULTI_POLYGON;
            case GEOMETRY_COLLECTION:
                return GeometryTypeName.GEOMETRY_COLLECTION;
            default:
                throw new IllegalArgumentException("Invalid Geometry Type: " + type);
        }
    }

    /**
     * Copy of com.esri.core.geometry.Interop.translateFromAVNaN
     *
     * deserializeEnvelope needs to recognize custom NAN values generated by
     * ESRI's serialization of empty geometries.
     */
    private static double translateFromAVNaN(double n)
    {
        return n < -1.0E38D ? (0.0D / 0.0) : n;
    }

    private static boolean isEsriNaN(double d)
    {
        return Double.isNaN(d) || Double.isNaN(translateFromAVNaN(d));
    }

    @Nullable
    public static Envelope deserializeEnvelope(Slice shape)
    {
        if (shape == null) {
            return null;
        }
        BasicSliceInput input = shape.getInput();

        Envelope overallEnvelope = null;
        if (input.available() > 0) {
            byte code = input.readByte();
            boolean isGeometryCollection = (code == GeometryTypeName.GEOMETRY_COLLECTION.code());
            while (input.available() > 0) {
                int length = isGeometryCollection ? input.readInt() : input.available();
                ByteBuffer buffer = input.readSlice(length).toByteBuffer().order(LITTLE_ENDIAN);
                int type = buffer.getInt();
                Envelope envelope = null;
                if (type == POINT_TYPE) {    // point
                    double x = buffer.getDouble();
                    double y = buffer.getDouble();
                    if (!isEsriNaN(x)) {
                        Verify.verify(!isEsriNaN(y));
                        envelope = new Envelope(x, y, x, y);
                    }
                }
                else {
                    double xMin = buffer.getDouble();
                    double yMin = buffer.getDouble();
                    double xMax = buffer.getDouble();
                    double yMax = buffer.getDouble();
                    if (!isEsriNaN(xMin)) {
                        Verify.verify(!isEsriNaN(xMax));
                        Verify.verify(!isEsriNaN(yMin));
                        Verify.verify(!isEsriNaN(yMax));
                        envelope = new Envelope(xMin, yMin, xMax, yMax);
                    }
                }
                if (envelope != null) {
                    if (overallEnvelope == null) {
                        overallEnvelope = envelope;
                    }
                    else {
                        overallEnvelope.merge(envelope);
                    }
                }
            }
        }

        return overallEnvelope;
    }

    public static OGCGeometry deserialize(Slice shape)
    {
        if (shape == null) {
            return null;
        }
        BasicSliceInput input = shape.getInput();

        // GeometryCollection: geometryType|len-of-shape1|bytes-of-shape1|len-of-shape2|bytes-of-shape2...
        List<OGCGeometry> geometries = new ArrayList<>();

        if (input.available() > 0) {
            byte code = input.readByte();
            boolean isGeometryCollection = (code == GeometryTypeName.GEOMETRY_COLLECTION.code());
            while (input.available() > 0) {
                geometries.add(readGeometry(isGeometryCollection, input));
            }
        }

        if (geometries.isEmpty()) {
            return new OGCConcreteGeometryCollection(emptyList(), null);
        }
        else if (geometries.size() == 1) {
            return geometries.get(0);
        }
        return new OGCConcreteGeometryCollection(geometries, null);
    }

    private static OGCGeometry readGeometry(boolean isGeometryCollection, BasicSliceInput input)
    {
        int length = isGeometryCollection ? input.readInt() : input.available();
        ByteBuffer buffer = input.readSlice(length).toByteBuffer().slice().order(LITTLE_ENDIAN);
        Geometry esriGeometry = local().execute(0, Unknown, buffer);
        return createFromEsriGeometry(esriGeometry, null);
    }

    public static Slice serialize(OGCGeometry input)
    {
        DynamicSliceOutput sliceOutput = new DynamicSliceOutput(100);

        sliceOutput.appendByte(valueOf(input.geometryType()).code());
        GeometryCursor cursor = input.getEsriGeometryCursor();
        boolean isGeometryCollection = input.geometryType().equals(GEOMETRY_COLLECTION);
        while (true) {
            Geometry geometry = cursor.next();
            if (geometry == null) {
                break;
            }
            byte[] shape = geometryToEsriShape(geometry);
            if (isGeometryCollection) {
                sliceOutput.appendInt(shape.length);
            }
            sliceOutput.appendBytes(shape);
        }
        return sliceOutput.slice();
    }

    public static long getEstimatedMemorySizeInBytes(OGCGeometry geometry)
    {
        return GeometryMemorySizeUtilsPackageWorkaround.getEstimatedMemorySizeInBytes(geometry);
    }
}
