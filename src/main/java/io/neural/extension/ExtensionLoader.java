package io.neural.extension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ExtensionLoader
 * 
 * @author lry
 *
 * @param <T>
 */
public class ExtensionLoader<T> {

	private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private Class<T> type;
    private ClassLoader classLoader;
    private volatile boolean init = false;
    private static final String PREFIX = "META-INF/services/";
    private ConcurrentMap<String, T> singletonInstances = null;
    private ConcurrentMap<String, Class<T>> extensionClasses = null;
    private static ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoaders = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    private ExtensionLoader(Class<T> type, ClassLoader classLoader) {
        this.type = type;
        this.classLoader = classLoader;
    }

    private void checkInit() {
        if (!init) {
            loadExtensionClasses();
        }
    }

    public Class<T> getExtensionClass(String name) {
        this.checkInit();
        return extensionClasses.get(name);
    }

    public T getExtension() {
    	this.checkInit();
    	 
    	NSPI nspi = type.getAnnotation(NSPI.class);
    	if(nspi.value() == null || nspi.value().length() == 0){
        	throw new RuntimeException(type.getName() + ": The default implementation ID(@NSPI.value()) is not set");
        } else {
        	try {
                if (nspi.single()) {
                    return this.getSingletonInstance(nspi.value());
                } else {
                    Class<T> clz = extensionClasses.get(nspi.value());
                    if (clz == null) {
                        return null;
                    }

                    return clz.newInstance();
                }
            } catch (Exception e) {
            	throw new RuntimeException(type.getName() + ": Error when getExtension ", e);
            }
        }
    }
    
    public T getExtension(String name) {
    	this.checkInit();
        if (name == null) {
            return null;
        }

        try {
            NSPI nspi = type.getAnnotation(NSPI.class);
            if (nspi.single()) {
                return this.getSingletonInstance(name);
            } else {
                Class<T> clz = extensionClasses.get(name);
                if (clz == null) {
                    return null;
                }

                return clz.newInstance();
            }
        } catch (Exception e) {
        	throw new RuntimeException(type.getName() + ": Error when getExtension ", e);
        }
    }

    private T getSingletonInstance(String name) throws InstantiationException, IllegalAccessException {
        T obj = singletonInstances.get(name);
        if (obj != null) {
            return obj;
        }

        Class<T> clz = extensionClasses.get(name);
        if (clz == null) {
            return null;
        }

        synchronized (singletonInstances) {
            obj = singletonInstances.get(name);
            if (obj != null) {
                return obj;
            }
            obj = clz.newInstance();
            singletonInstances.put(name, obj);
        }

        return obj;
    }

    public void addExtensionClass(Class<T> clz) {
        if (clz == null) {
            return;
        }

        checkInit();
        checkExtensionType(clz);
        String nspiName = getSpiName(clz);
        synchronized (extensionClasses) {
            if (extensionClasses.containsKey(nspiName)) {
            	throw new RuntimeException(clz.getName() + ": Error nspiName already exist " + nspiName);
            } else {
                extensionClasses.put(nspiName, clz);
            }
        }
    }

    private synchronized void loadExtensionClasses() {
        if (init) {
            return;
        }

        extensionClasses = loadExtensionClasses(PREFIX);
        singletonInstances = new ConcurrentHashMap<String, T>();
        init = true;
    }

    public static <T> ExtensionLoader<T> getLoader(Class<T> type) {
    	return getLoader(type, Thread.currentThread().getContextClassLoader());
    }
    
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getLoader(Class<T> type, ClassLoader classLoader) {
        if (type == null) {
        	throw new RuntimeException("Error extension type is null");
        }
        if (!type.isAnnotationPresent(NSPI.class)) {
        	throw new RuntimeException(type.getName() + ": Error extension type without @NSPI annotation");
        }
        
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoaders.get(type);
        if (loader == null) {
            loader = initExtensionLoader(type, classLoader);
        }
        
        return loader;
    }

    public static synchronized <T> ExtensionLoader<T> initExtensionLoader(Class<T> type) {
    	return initExtensionLoader(type, Thread.currentThread().getContextClassLoader());
	}
    
    @SuppressWarnings("unchecked")
    public static synchronized <T> ExtensionLoader<T> initExtensionLoader(Class<T> type, ClassLoader classLoader) {
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoaders.get(type);
        if (loader == null) {
            loader = new ExtensionLoader<T>(type, classLoader);
            extensionLoaders.putIfAbsent(type, loader);
            loader = (ExtensionLoader<T>) extensionLoaders.get(type);
        }

        return loader;
    }

    public List<T> getExtensions() {
    	return this.getExtensions("");
    }
    
    /**
     * 有些地方需要nspi的所有激活的instances，所以需要能返回一个列表的方法<br>
     * <br>
     * 注意：<br>
     * 1 SpiMeta 中的active 为true<br> 
     * 2 按照nspiMeta中的order进行排序 <br>
     * <br>
     * FIXME： 是否需要对singleton来区分对待，后面再考虑 fishermen
     * 
     * @return
     */
    public List<T> getExtensions(String key) {
        checkInit();
        if (extensionClasses.size() == 0) {
            return Collections.emptyList();
        }

        // 如果只有一个实现，直接返回
        List<T> exts = new ArrayList<T>(extensionClasses.size());
        // 多个实现，按优先级排序返回
        for (Map.Entry<String, Class<T>> entry : extensionClasses.entrySet()) {
            Extension extension = entry.getValue().getAnnotation(Extension.class);
            if (key==null||key.length()==0) {
                exts.add(getExtension(entry.getKey()));
            } else if (extension != null && extension.category() != null) {
                for (String k : extension.category()) {
                    if (key.equals(k)) {
                        exts.add(getExtension(entry.getKey()));
                        break;
                    }
                }
            }
        }
        
        // order 大的排在后面,如果没有设置order的排到最前面
        Collections.sort(exts, new Comparator<T>() {
        	@Override
        	public int compare(T o1, T o2) {
        		Extension p1 = o1.getClass().getAnnotation(Extension.class);
        		Extension p2 = o2.getClass().getAnnotation(Extension.class);
                if (p1 == null) {
                    return 1;
                } else if (p2 == null) {
                    return -1;
                } else {
                    return p1.order() - p2.order();
                }
        	}
		});
        
        return exts;
    }

    private void checkExtensionType(Class<T> clz) {
    	// 1) is public class
    	if (!type.isAssignableFrom(clz)) {
        	throw new RuntimeException(clz.getName() + ": Error is not instanceof " + type.getName());
        }
    	
    	// 2) contain public constructor and has not-args constructor
    	Constructor<?>[] constructors = clz.getConstructors();
        if (constructors == null || constructors.length == 0) {
            throw new RuntimeException(clz.getName() + ": Error has no public no-args constructor");
        }

        for (Constructor<?> constructor : constructors) {
            if (Modifier.isPublic(constructor.getModifiers()) && constructor.getParameterTypes().length == 0) {
            	// 3) check extension clz instanceof Type.class
            	if (!type.isAssignableFrom(clz)) {
                	throw new RuntimeException(clz.getName() + ": Error is not instanceof " + type.getName());
                }
            	
            	return;
            }
        }

        throw new RuntimeException(clz.getName() + ": Error has no public no-args constructor");
    }

    private ConcurrentMap<String, Class<T>> loadExtensionClasses(String prefix) {
        String fullName = prefix + type.getName();
        List<String> classNames = new ArrayList<String>();

        try {
            Enumeration<URL> urls;
            if (classLoader == null) {
                urls = ClassLoader.getSystemResources(fullName);
            } else {
                urls = classLoader.getResources(fullName);
            }

            if (urls == null || !urls.hasMoreElements()) {
                return new ConcurrentHashMap<String, Class<T>>();
            }

            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                this.parseUrl(type, url, classNames);
            }
        } catch (Exception e) {
            throw new RuntimeException("ExtensionLoader loadExtensionClasses error, prefix: " + prefix + " type: " + type.getClass(), e);
        }

        return loadClass(classNames);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, Class<T>> loadClass(List<String> classNames) {
        ConcurrentMap<String, Class<T>> map = new ConcurrentHashMap<String, Class<T>>();
        for (String className : classNames) {
            try {
                Class<T> clz;
                if (classLoader == null) {
                    clz = (Class<T>) Class.forName(className);
                } else {
                    clz = (Class<T>) Class.forName(className, true, classLoader);
                }

                this.checkExtensionType(clz);
                String nspiName = this.getSpiName(clz);
                if (map.containsKey(nspiName)) {
                    throw new RuntimeException(clz.getName() + ": Error nspiName already exist " + nspiName);
                } else {
                    map.put(nspiName, clz);
                }
            } catch (Exception e) {
            	logger.error(type.getName() + ": Error load nspi class", e);
            }
        }

        return map;

    }

    private String getSpiName(Class<?> clz) {
        Extension extension = clz.getAnnotation(Extension.class);
        return (extension != null && !"".equals(extension.value())) ? extension.value() : clz.getSimpleName();
    }

    private void parseUrl(Class<T> type, URL url, List<String> classNames) throws ServiceConfigurationError {
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            inputStream = url.openStream();
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

            String line = null;
            int indexNumber = 0;
            while ((line = reader.readLine()) != null) {
                indexNumber++;
                this.parseLine(type, url, line, indexNumber, classNames);
            }
        } catch (Exception e) {
        	logger.error(type.getName() + ": Error reading nspi configuration file", e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
            	logger.error(type.getName() + ": Error closing nspi configuration file", e);
            }
        }
    }

    private void parseLine(Class<T> type, URL url, String line, int lineNumber, List<String> names) throws IOException, ServiceConfigurationError {
        int ci = line.indexOf('#');
        if (ci >= 0) {
            line = line.substring(0, ci);
        }

        line = line.trim();
        if (line.length() <= 0) {
            return;
        }

        if ((line.indexOf(' ') >= 0) || (line.indexOf('\t') >= 0)) {
        	throw new RuntimeException(type.getName() + ": " + url + ":" + line + ": Illegal nspi configuration-file syntax: " + line);
        }

        int cp = line.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) {
            throw new RuntimeException(type.getName() + ": " + url + ":" + line + ": Illegal nspi provider-class name: " + line);
        }

        for (int i = Character.charCount(cp); i < line.length(); i += Character.charCount(cp)) {
            cp = line.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp) && (cp != '.')) {
                throw new RuntimeException(type.getName() + ": " + url + ":" + line + ": Illegal nspi provider-class name: " + line);
            }
        }

        if (!names.contains(line)) {
            names.add(line);
        }
    }

}