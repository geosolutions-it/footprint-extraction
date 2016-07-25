package it.geosolutions.footprint;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geotools.process.raster.MarchingSquaresVectorizer.ImageLoadingType;
import org.geotools.util.Range;

/**
 * A simple class to deal with footprint extraction processing parameters
 * 
 * @author Daniele Romagnoli, GeoSolutions SAS
 * 
 */
public class FootprintParameter {

    /**
     * Set of FootprintParameter keys
     */
    static class Key {
        final static String THRESHOLD_AREA = "thresholdArea";

        final static String EXCLUSION_RANGES = "exclusionRanges";

        final static String COMPUTE_SIMPLIFIED_FOOTPRINT = "computeSimplifiedFootprint";

        final static String SIMPLIFIER_FACTOR = "simplifierFactor";

        final static String REMOVE_COLLINEAR = "removeCollinear";

        final static String FORCE_VALID = "forceValid";

        final static String LOADING_TYPE = "loadingType";

    }

    /**
     * Set of FootprintParameter default definitions.
     */
    static class Default {
        final static double THRESHOLD_AREA = 100;

        final static List<Range<Integer>> EXCLUSION_RANGES = Collections
                .singletonList(new Range<Integer>(Integer.class, 0, 10));

        final static boolean REMOVE_COLLINEAR = true;

        final static boolean COMPUTE_SIMPLIFIED_FOOTPRINT = false;

        final static Double SIMPLIFIER_FACTOR = null;

        final static boolean FORCE_VALID = true;

        final static ImageLoadingType LOADING_TYPE = ImageLoadingType.getDefault();
    }

    final static Map<String, Object> DEFAULT_PARAMS;

    final static Set<String> PARAMS_KEY;

    /**
     * Initialize the set of parameters keys
     * 
     * @return
     */
    static Set<String> initKeys() {
        Set<String> keySet = new HashSet<String>();
        keySet.add(Key.COMPUTE_SIMPLIFIED_FOOTPRINT);
        keySet.add(Key.EXCLUSION_RANGES);
        keySet.add(Key.FORCE_VALID);
        keySet.add(Key.LOADING_TYPE);
        keySet.add(Key.REMOVE_COLLINEAR);
        keySet.add(Key.SIMPLIFIER_FACTOR);
        keySet.add(Key.THRESHOLD_AREA);

        return keySet;
    }

    /**
     * Initialize defaults Parameters map
     * 
     * @return
     */
    static Map<String, Object> initDefaults() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Key.EXCLUSION_RANGES, Default.EXCLUSION_RANGES);
        params.put(Key.THRESHOLD_AREA, Default.THRESHOLD_AREA);
        params.put(Key.REMOVE_COLLINEAR, Default.REMOVE_COLLINEAR);
        params.put(Key.COMPUTE_SIMPLIFIED_FOOTPRINT, Default.COMPUTE_SIMPLIFIED_FOOTPRINT);
        params.put(Key.FORCE_VALID, Default.FORCE_VALID);
        params.put(Key.LOADING_TYPE, Default.LOADING_TYPE);
        params.put(Key.SIMPLIFIER_FACTOR, Default.SIMPLIFIER_FACTOR);
        return params;
    }

    static {
        DEFAULT_PARAMS = FootprintParameter.initDefaults();
        PARAMS_KEY = FootprintParameter.initKeys();
    }

    /**
     * Parse the provided parameter map. Add default values when missing.
     * 
     * @param params
     * @return
     */
    static Map<String, Object> parseParams(Map<String, Object> params) {
        Map<String, Object> updatedParams = params;
        if (updatedParams == null) {
            return DEFAULT_PARAMS;
        }

        // This set will contains all the parameters keys which
        // haven't been found in the input parameter list in order
        // to use default values for them
        Set<String> needsDefaultsSet = new HashSet<String>();

        for (String key : PARAMS_KEY) {
            // Checking boolean parameters
            if (key.equalsIgnoreCase(Key.COMPUTE_SIMPLIFIED_FOOTPRINT)
                    || key.equalsIgnoreCase(Key.FORCE_VALID)
                    || key.equalsIgnoreCase(Key.REMOVE_COLLINEAR)) {
                booleanCheck(updatedParams, key, needsDefaultsSet);

            } 
            // checking double parameters
            else if (key.equalsIgnoreCase(Key.THRESHOLD_AREA)
                    || key.equalsIgnoreCase(Key.SIMPLIFIER_FACTOR)) {
                doubleValueCheck(updatedParams, key, needsDefaultsSet);
            } 
            // Checking exclusion ranges
            else if (key.equalsIgnoreCase(Key.EXCLUSION_RANGES)) {
                Object param = updatedParams.get(key);
                if (param != null && param instanceof List) {
                    List list = (List) param;
                    if (!list.isEmpty() && (list.get(0) instanceof Range)) {
                        Range range = (Range) list.get(0);
                        if (range.getElementClass() == Integer.class) {
                            continue;
                        }
                    }
                }
                needsDefaultsSet.add(key);
            } 
            // Checking loading type
            else if (key.equalsIgnoreCase(Key.LOADING_TYPE)) {
                Object param = updatedParams.get(key);
                if (param == null || !(param instanceof ImageLoadingType)) {
                    needsDefaultsSet.add(key);
                }
            }
        }

        for (String key : needsDefaultsSet) {
            updatedParams.put(key, DEFAULT_PARAMS.get(key));
        }
        return updatedParams;
    }

    /**
     * Check whether the parameters map contains the specified key and that param
     * is a double. In case the check fails, the key is added to the set of keys
     * which require a default parameter value.
     * 
     * @param updatedParams
     * @param key
     * @param needsDefaultsSet
     */
    private static void doubleValueCheck(Map<String, Object> updatedParams, String key,
            Set<String> needsDefaultsSet) {
        Object param = updatedParams.get(key);
        if (param == null || !(param instanceof Double)) {
            needsDefaultsSet.add(key);
        }

    }

    /**
     * Check whether the parameters map contains the specified key and that param
     * is a boolean. In case the check fails, the key is added to the set of keys
     * which require a default parameter value.
     * 
     * @param updatedParams
     * @param key
     * @param needsDefaultsSet
     */
    private static void booleanCheck(Map<String, Object> updatedParams, String key,
            Set<String> needsDefaultsSet) {
        Object param = updatedParams.get(key);
        if (param == null || !(param instanceof Boolean)) {
            needsDefaultsSet.add(key);
        }

    }

}
