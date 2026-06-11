package org.tmforum.intent.api;

import java.util.*;

/** Applies TMF sparse-fieldset projection to response maps. */
public class FieldsFilter {

    private FieldsFilter() {}

    public static Map<String, Object> apply(Map<String, Object> resource, String fields) {
        if (fields == null || fields.isBlank()) return resource;
        Set<String> allowed = new HashSet<>(Arrays.asList(fields.split(",")));
        allowed.add("id");
        allowed.add("href");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowed) {
            if (resource.containsKey(key)) result.put(key, resource.get(key));
        }
        return result;
    }

    public static List<Map<String, Object>> apply(List<Map<String, Object>> resources, String fields) {
        if (fields == null || fields.isBlank()) return resources;
        List<Map<String, Object>> result = new ArrayList<>(resources.size());
        for (Map<String, Object> r : resources) result.add(apply(r, fields));
        return result;
    }
}
