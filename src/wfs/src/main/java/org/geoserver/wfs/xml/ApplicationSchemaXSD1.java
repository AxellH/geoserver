/* (c) 2016 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wfs.xml;

import org.eclipse.xsd.XSDFactory;
import org.eclipse.xsd.XSDImport;
import org.eclipse.xsd.XSDSchema;
import org.eclipse.xsd.impl.XSDSchemaImpl;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.ows.URLMangler.URLType;
import org.geoserver.ows.util.ResponseUtils;
import org.geotools.xml.XSD;
import org.geotools.xs.XSSchema;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.w3c.dom.Document;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ApplicationSchemaXSD1 extends XSD {

    FeatureTypeSchemaBuilder schemaBuilder;

    Map<String,Set<FeatureTypeInfo>> featureTypes;
    String baseURL;

    public ApplicationSchemaXSD1(FeatureTypeSchemaBuilder schemaBuilder) {
        this(schemaBuilder, Collections.emptyMap());
    }

    public ApplicationSchemaXSD1(FeatureTypeSchemaBuilder schemaBuilder,
                                 Map<String,Set<FeatureTypeInfo>> featureTypes) {
        this.schemaBuilder = schemaBuilder;
        this.featureTypes = featureTypes;
    }

    public void setBaseURL(String baseURL) {
        this.baseURL = baseURL;
    }

    public String getBaseURL() {
        return baseURL;
    }

    public void setResources(Map<String, Set<ResourceInfo>> resources) {
        Map<String, Set<FeatureTypeInfo>> featureTypes = new HashMap<>();
        for (Map.Entry<String, Set<ResourceInfo>> entry : resources.entrySet()) {
            Set<FeatureTypeInfo> fts = new HashSet<>();
            for(ResourceInfo ri : entry.getValue()) {
                if(ri instanceof FeatureTypeInfo) {
                    fts.add((FeatureTypeInfo) ri);
                }
            }

            if(!fts.isEmpty()) {
                featureTypes.put(entry.getKey(), fts);
            }
        }
        this.featureTypes = featureTypes;
    }

    public Map<String, Set<FeatureTypeInfo>> getFeatureTypes() {
        return featureTypes;
    }
    
    @Override
    public String getNamespaceURI() {
        if (featureTypes.size() == 1) {
            return featureTypes.keySet().iterator().next();
        }
        
        //TODO: return xsd namespace?
        return null;
    }
    
    @Override
    public String getSchemaLocation() {
        StringBuilder sb = new StringBuilder();
        for (Set<FeatureTypeInfo> fts : featureTypes.values()) {
            for (FeatureTypeInfo ft : fts) {
                sb.append(ft.getPrefixedName()).append(",");
            }
        }
        sb.setLength(sb.length()-1);
        
        HashMap kvp = new HashMap();
        kvp.putAll(schemaBuilder.getDescribeFeatureTypeParams());
        kvp.put("typename", sb.toString());
        
        return ResponseUtils.buildURL(baseURL, "wfs", kvp, URLType.SERVICE);
    }
    
    @Override
    protected XSDSchema buildSchema() throws IOException {
        FeatureTypeInfo[] types = this.featureTypes.values().stream()
                .flatMap(Collection::stream).toArray(FeatureTypeInfo[]::new);
        XSDSchema schema;
        if (containsComplexTypes(types)) {
            // we have complex features so we add all the available catalog feature types
            schema = schemaBuilder.build(new FeatureTypeInfo[0], baseURL, true, true);
            schemaBuilder.addApplicationTypes(schema);
        } else {
            // simple feature so we add only the feature types we need
            schema = schemaBuilder.build(types, baseURL, true, true);
        }
        // add an explicit dependency on WFS 1.0.0 schema
        return importWfsSchema(schema);
    }

    /**
     * Checks if the provided feature types contains complex types.
     */
    private static boolean containsComplexTypes(FeatureTypeInfo[] featureTypes) {
        for (FeatureTypeInfo featureType : featureTypes) {
            try {
                if(!(featureType.getFeatureType() instanceof SimpleFeatureType)) {
                    return true;
                }
            }
            catch(Exception exception) {
                // ignore the broken feature type
            }
        }
        return false;
    }

    /**
     * Imports the WFS 1.0.0 schema as a dependency.
     */
    private static XSDSchema importWfsSchema(XSDSchema schema) throws IOException {
        XSDSchema wfsSchema = org.geotools.wfs.v1_1.WFS.getInstance().getSchema();
        if (wfsSchema == null || !(wfsSchema instanceof XSDSchemaImpl)) {
            return schema;
        }
        XSDImport wfsImport = XSDFactory.eINSTANCE.createXSDImport();
        wfsImport.setNamespace(org.geotools.wfs.v1_1.WFS.NAMESPACE);
        wfsImport.setResolvedSchema(wfsSchema);
        schema.getContents().add(wfsImport);
        schema.getQNamePrefixToNamespaceMap().put("wfs", org.geotools.wfs.v1_1.WFS.NAMESPACE);
        synchronized (wfsSchema.eAdapters()) {
            ((XSDSchemaImpl) wfsSchema).imported(wfsImport);
        }
        return schema;
    }
}