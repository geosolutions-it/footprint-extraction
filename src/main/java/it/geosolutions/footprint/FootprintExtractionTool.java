/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2014, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.footprint;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.media.jai.JAI;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.DataSourceException;
import org.geotools.data.DataStoreFactorySpi;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureSource;
import org.geotools.data.FeatureStore;
import org.geotools.data.Transaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.process.raster.FootprintExtractionProcess;
import org.geotools.process.raster.MarchingSquaresVectorizer.ImageLoadingType;
import org.geotools.util.Range;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.io.OutStream;
import com.vividsolutions.jts.io.OutputStreamOutStream;
import com.vividsolutions.jts.io.WKBWriter;
import com.vividsolutions.jts.io.WKTWriter;

/**
 * Simple tool to compute {@link FootprintExtractionProcess} on a provided GeoTIFF File.
 * 
 * Just invoke the {@link FootprintExtractionTool#generateFootprint(FootprintProcessingInputBean)}
 * method by providing a {@link FootprintProcessingInputBean} input bean containing the input 
 * GeoTIFF {@link File} to be processed. The output is written as a WKB beside the original GeoTIFF.
 * 
 * By Default, footprint extraction: 
 * - uses a luminance threshold in the range 0 - 10,
 * - does remove collinear
 * - excludes polygon having pixel area lower than 100 pixels
 * - doesn't compute a simplified version too
 * - uses deferred execution based image loading
 * 
 * These parameters can be customized by providing a Map<String, Object> to the
 * input bean. See {@link FootprintParameter} for the name of the Parameter Keys
 * as well as the values of the Defaults which will be used in case of missing
 * parameter. 
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 */
public class FootprintExtractionTool {

    private static final String THE_GEOM = "the_geom";

    private static final String CREATE_SPATIAL_INDEX = "create spatial index";

    private static final double TOLERANCE = 1.0e-12;

    private static FootprintExtractionProcess process = null;
    
    private static final String JAI_CACHE = "footprint.cache";
    
    private static long JAI_CACHE_SIZE = 1024; 

    static {
        process = new FootprintExtractionProcess();
        String jaiCache = System.getProperty(JAI_CACHE);
        if (jaiCache != null && !jaiCache.isEmpty()) {
            JAI_CACHE_SIZE = Integer.parseInt(jaiCache);
        }
        JAI.getDefaultInstance().getTileCache().setMemoryCapacity(1024*1024*JAI_CACHE_SIZE);
        
    }

    static enum WritingFormat {
        WKB {
            @Override
            void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
                    throws IOException {
                final WKBWriter wkbWriter = new WKBWriter(2);
                final OutputStream outputStream = new FileOutputStream(outputFile);
                final BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
                final OutStream outStream = new OutputStreamOutStream(bufferedStream);
                try {
                    wkbWriter.write(geometry, outStream);
                } finally {
                    IOUtils.closeQuietly(bufferedStream);
                }
            }

            @Override
            String getExtension() {
                return ".wkb";
            }
        },
        WKT {
            @Override
            void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
                    throws IOException {
                final WKTWriter wktWriter = new WKTWriter(2);
                final StringWriter wkt = new StringWriter();
                BufferedWriter bufferedWriter = new BufferedWriter(wkt);
                try {
                    wktWriter.write(geometry, bufferedWriter);
                } finally {
                    IOUtils.closeQuietly(bufferedWriter);
                }

                // write to file
                if (outputFile != null) {

                    bufferedWriter = new BufferedWriter(new FileWriter(outputFile));
                    try {
                        bufferedWriter.write(wkt.toString());
                    } finally {
                        IOUtils.closeQuietly(bufferedWriter);
                    }
                }
            }

            @Override
            String getExtension() {
                return ".wkt";
            }
        },
        SHAPEFILE {
            @Override
            void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
                    throws IOException {

                // create feature type
                final SimpleFeatureTypeBuilder featureTypeBuilder = new SimpleFeatureTypeBuilder();
                featureTypeBuilder.setName("raster2vector");
                featureTypeBuilder.setCRS(crs);
                featureTypeBuilder.add(THE_GEOM, Polygon.class);
                featureTypeBuilder.add("cat", Integer.class);
                SimpleFeatureType featureType = featureTypeBuilder.buildFeatureType();

                // Preparing the collection
                final ListFeatureCollection collection = new ListFeatureCollection(featureType);
                final String typeName = featureType.getTypeName();

                // Creating the feature
                final SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
                final Object[] values = new Object[] { geometry, 0 };
                featureBuilder.addAll(values);
                final SimpleFeature feature = featureBuilder.buildFeature(typeName + '.' + 0);

                // adding the feature to the collection
                collection.add(feature);

                // create shapefile
                final DataStoreFactorySpi factory = new ShapefileDataStoreFactory();

                // Preparing creation param
                final Map<String, Serializable> params = new HashMap<String, Serializable>();
                params.put(CREATE_SPATIAL_INDEX, Boolean.TRUE);
                params.put("url", DataUtilities.fileToURL(outputFile));

                final ShapefileDataStore ds = (ShapefileDataStore) factory
                        .createNewDataStore(params);
                ds.createSchema(featureType);
                if (crs != null) {
                    ds.forceSchemaCRS(crs);
                }

                // Write the features to the shapefile
                Transaction transaction = new DefaultTransaction("create");

                FeatureSource source = ds.getFeatureSource(ds.getTypeNames()[0]);

                if (source instanceof FeatureStore) {
                    FeatureStore store = (FeatureStore) source;

                    store.setTransaction(transaction);
                    try {
                        store.addFeatures(collection);
                        transaction.commit();

                    } catch (Exception e) {
                        transaction.rollback();

                    } finally {
                        try {
                            transaction.close();
                        } catch (IOException ioe) {

                        }
                    }
                }
                ds.dispose();

            }
            
            @Override
            String getExtension() {
                return ".shp";
            }
        };

        abstract void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
                throws IOException;
        
        abstract String getExtension();
    }
    
    /**
     * {@link FootprintProcessingInputBean} defining the input of the processing.
     * 
     * Requested parameters are the input {@link File} to be processed as well as a 
     * map of footprint extraction parameters. The footprint parameters
     * may be null; in that case, default will be used. 
     * See {@link FootprintParameter#initDefaults()}
     * 
     * 
     */
    public static class FootprintProcessingInputBean {
        File inputFile;

        Map<String, Object> footprintParameters;

        public Map<String, Object> getFootprintParameters() {
            return footprintParameters;
        }

        public void setFootprintParameters(Map<String, Object> footprintParameters) {
            this.footprintParameters = footprintParameters;
        }

        public File getInputFile() {
            return inputFile;
        }

        public void setInputFile(File inputFile) {
            this.inputFile = inputFile;
        }
    }

    /**
     * FootprintProcessingOutputBean
     * 
     * Check for empty list of exceptions when processing is done.
     */
    public static class FootprintProcessingOutputBean {
        List<Exception> exceptions;
        
        WritingFormat simplifiedFormat;
        
        WritingFormat preciseFormat;
        
        public WritingFormat getSimplifiedFormat() {
            return simplifiedFormat;
        }

        public void setSimplifiedFormat(WritingFormat simplifiedFormat) {
            this.simplifiedFormat = simplifiedFormat;
        }

        public WritingFormat getPreciseFormat() {
            return preciseFormat;
        }

        public void setPreciseFormat(WritingFormat preciseFormat) {
            this.preciseFormat = preciseFormat;
        }

        public List<Exception> getExceptions() {
            return exceptions;
        }

        public FootprintProcessingOutputBean() {
            this.exceptions = new ArrayList<Exception>();
        }

        public void addException(Exception e) {
            exceptions.add(e);
        }
    }

    /**
     * Method to generate the footprint and create a geometry file beside the input file. 
     * @param inputBean
     * 
     * @return
     */
    public static FootprintProcessingOutputBean generateFootprint(
            FootprintProcessingInputBean inputBean) {
        return generateFootprint(inputBean, null);
    }
    
    /**
     * Method to generate the footprint and create a geometry file beside the input file. 
     * @param inputBean
     * @param outputBean an optional outputBean specifying required format
     * 
     * @return
     */
    public static FootprintProcessingOutputBean generateFootprint(
            FootprintProcessingInputBean inputBean, FootprintProcessingOutputBean outputBean) {

        FootprintProcessingOutputBean output = 
            outputBean != null ? outputBean : new FootprintProcessingOutputBean();

        GeoTiffReader reader = null;
        FeatureIterator<SimpleFeature> iter = null;
        GridCoverage2D cov = null;
        WritingFormat writingFormat = output.getPreciseFormat() != null ? output.getPreciseFormat() : 
            WritingFormat.WKB;
        WritingFormat simplfiedFormat = output.getSimplifiedFormat() != null ? output.getSimplifiedFormat() : 
            WritingFormat.WKB;

        try {

            // Accessing the dataset
            final File inputFile = inputBean.getInputFile();
            final String fileName = inputFile.getCanonicalPath();
            reader = new GeoTiffReader(inputFile);
            cov = reader.read(null);

            // Preparing the footprint processing parameters
            Map<String, Object> params = FootprintParameter.parseParams(inputBean.getFootprintParameters());

            SimpleFeatureCollection fc = process.execute(cov,
                    (List<Range<Integer>>) params.get(FootprintParameter.Key.EXCLUSION_RANGES),
                    (Double) params.get(FootprintParameter.Key.THRESHOLD_AREA),
                    (Boolean) params.get(FootprintParameter.Key.COMPUTE_SIMPLIFIED_FOOTPRINT),
                    (Double) params.get(FootprintParameter.Key.SIMPLIFIER_FACTOR),
                    (Boolean) params.get(FootprintParameter.Key.REMOVE_COLLINEAR),
                    (Boolean) params.get(FootprintParameter.Key.FORCE_VALID),
                    (ImageLoadingType) params.get(FootprintParameter.Key.LOADING_TYPE), null);

            // Getting the computed features
            iter = fc.features();

            // First feature is main footprint
            SimpleFeature feature = iter.next();
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            final String basePath = FilenameUtils.getFullPath(fileName);
            final String baseName = FilenameUtils.getBaseName(fileName);
            final String outputName = baseName + writingFormat.getExtension();
            File outputFile = new File(FilenameUtils.concat(basePath, outputName));
            if (outputFile.exists()) {
                FileUtils.deleteQuietly(outputFile);
            }
            CoordinateReferenceSystem crs = cov.getCoordinateReferenceSystem();

            // writing the precise footprint
            writingFormat.write(geometry, outputFile, crs);

            if (iter.hasNext()) {
                // Write simplified footprint too
                feature = iter.next();
                geometry = (Geometry) feature.getDefaultGeometry();
                final String simplifiedOutputName = baseName + "_simplified" + simplfiedFormat.getExtension();
                outputFile = new File(FilenameUtils.concat(basePath, simplifiedOutputName));
                if (outputFile.exists()) {
                    FileUtils.deleteQuietly(outputFile);
                }
                simplfiedFormat.write(geometry, outputFile, crs);

            }

        } catch (DataSourceException e) {
            output.addException(e);
        } catch (IOException e) {
            output.addException(e);
        } finally {
            if (iter != null) {
                iter.close();
            }
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Throwable t) {

                }
            }
            if (cov != null) {
                try {
                    cov.dispose(true);
                } catch (Throwable t) {

                }
            }
        }
        return output;
    }

    /**
     * Write the provided geometry to the specified output file.
     * 
     * @param geometry
     * @param outputFile
     * @param crs
     * @throws IOException
     */
    static void write(Geometry geometry, File outputFile, CoordinateReferenceSystem crs)
            throws IOException {
        final WKBWriter wkbWriter = new WKBWriter(2);
        final OutputStream outputStream = new FileOutputStream(outputFile);
        final BufferedOutputStream bufferedStream = new BufferedOutputStream(outputStream);
        final OutStream outStream = new OutputStreamOutStream(bufferedStream);
        try {
            wkbWriter.write(geometry, outStream);
        } finally {
            IOUtils.closeQuietly(bufferedStream);
        }

    }
    
    public static void main(String[] args) {
        String filePath = args[0];
        WritingFormat preciseFormat = null;
        WritingFormat simplifiedFormat = null;
        if (args.length > 1) {
            preciseFormat = WritingFormat.valueOf(args[1].toUpperCase());
        }
        if (args.length > 2) {
            simplifiedFormat = WritingFormat.valueOf(args[2].toUpperCase());
        }
            
        //============================================================
        FootprintProcessingInputBean inputBean = new FootprintProcessingInputBean();
        inputBean.setInputFile(new File(filePath));
        
        FootprintProcessingOutputBean outputBean = new FootprintProcessingOutputBean();
        outputBean.setPreciseFormat(preciseFormat);
        outputBean.setSimplifiedFormat(simplifiedFormat);

        //========== Set the parameters map to the inputBean ===============
        Map<String,Object> parameters = new HashMap<String, Object>();
        parameters.put(FootprintParameter.Key.THRESHOLD_AREA, 100);
        parameters.put(FootprintParameter.Key.FORCE_VALID, true);
        parameters.put(FootprintParameter.Key.REMOVE_COLLINEAR, true);
        parameters.put(FootprintParameter.Key.SIMPLIFIER_FACTOR, 2);
        parameters.put(FootprintParameter.Key.COMPUTE_SIMPLIFIED_FOOTPRINT, true);
        inputBean.setFootprintParameters(parameters);
        //============================================================
      
        outputBean = FootprintExtractionTool.generateFootprint(inputBean, outputBean);
        if (outputBean.getExceptions().isEmpty()) {
              // everything was ok. wkb file should have been created beside the original tif file
              // note that previous WKB file will be deleted if already existing
        }
        //============================================================

    }
}