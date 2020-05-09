package com.fasterxml.jackson.databind.util;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;


/**
 * Helper class that contains functionality needed by both serialization
 * and deserialization side.
 */
public class BeanUtil
{
    /*
    /**********************************************************
    /* Handling property names
    /**********************************************************
     */

    public static String okNameForGetter(AnnotatedMember am) {
        String name = am.getName();
        String str = okNameForIsGetter(am, name);
        if (str == null) {
            str = okNameForRegularGetter(am, name);
        }
        return str;
    }

    public static String okNameForRegularGetter(AnnotatedMember am, String name)
    {
        if (RecordUtil.isRecord(am.getDeclaringClass()) &&
                Arrays.asList(RecordUtil.getRecordComponents(am.getDeclaringClass())).contains(name)) {
            // record getters are not prefixed
            return name;
        }

        if (name.startsWith("get")) {
            /* 16-Feb-2009, tatu: To handle [JACKSON-53], need to block
             *   CGLib-provided method "getCallbacks". Not sure of exact
             *   safe criteria to get decent coverage without false matches;
             *   but for now let's assume there's no reason to use any
             *   such getter from CGLib.
             *   But let's try this approach...
             */
            if ("getCallbacks".equals(name)) {
                if (isCglibGetCallbacks(am)) {
                    return null;
                }
            } else if ("getMetaClass".equals(name)) {
                // 30-Apr-2009, tatu: Need to suppress serialization of a cyclic reference
                if (isGroovyMetaClassGetter(am)) {
                    return null;
                }
            }
            return stdManglePropertyName(name, 3);
        }
        return null;
    }

    public static String okNameForIsGetter(AnnotatedMember am, String name)
    {
        if (name.startsWith("is")) { // plus, must return a boolean
            Class<?> rt = am.getRawType();
            if (rt == Boolean.class || rt == Boolean.TYPE) {
                return stdManglePropertyName(name, 2);
            }
        }
        return null;
    }

    public static String okNameForMutator(AnnotatedMember am, String prefix)
    {
        String name = am.getName();
        if (name.startsWith(prefix)) {
            return stdManglePropertyName(name, prefix.length());
        }
        return null;
    }

    /*
    /**********************************************************
    /* Value defaulting helpers
    /**********************************************************
     */

    /**
     * Accessor used to find out "default value" to use for comparing values to
     * serialize, to determine whether to exclude value from serialization with
     * inclusion type of {@link com.fasterxml.jackson.annotation.JsonInclude.Include#NON_DEFAULT}.
     *<p>
     * Default logic is such that for primitives and wrapper types for primitives, expected
     * defaults (0 for `int` and `java.lang.Integer`) are returned; for Strings, empty String,
     * and for structured (Maps, Collections, arrays) and reference types, criteria
     * {@link com.fasterxml.jackson.annotation.JsonInclude.Include#NON_DEFAULT}
     * is used.
     */
    public static Object getDefaultValue(JavaType type)
    {
        // 06-Nov-2015, tatu: Returning null is fine for Object types; but need special
        //   handling for primitives since they are never passed as nulls.
        Class<?> cls = type.getRawClass();

        // 30-Sep-2016, tatu: Also works for Wrappers, so both `Integer.TYPE` and `Integer.class`
        //    would return `Integer.TYPE`
        Class<?> prim = ClassUtil.primitiveType(cls);
        if (prim != null) {
            return ClassUtil.defaultValue(prim);
        }
        if (type.isContainerType() || type.isReferenceType()) {
            return JsonInclude.Include.NON_EMPTY;
        }
        if (cls == String.class) {
            return "";
        }
        // 09-Mar-2016, tatu: Not sure how far this path we want to go but for now
        //   let's add `java.util.Date` and `java.util.Calendar`, as per [databind#1550]
        if (type.isTypeOrSubTypeOf(Date.class)) {
            return new Date(0L);
        }
        if (type.isTypeOrSubTypeOf(Calendar.class)) {
            Calendar c = new GregorianCalendar();
            c.setTimeInMillis(0L);
            return c;
        }
        return null;
    }

    /*
    /**********************************************************
    /* Special case handling
    /**********************************************************
     */

    /**
     * This method was added to address the need to weed out
     * CGLib-injected "getCallbacks" method.
     * At this point caller has detected a potential getter method
     * with name "getCallbacks" and we need to determine if it is
     * indeed injectect by Cglib. We do this by verifying that the
     * result type is "net.sf.cglib.proxy.Callback[]"
     */
    protected static boolean isCglibGetCallbacks(AnnotatedMember am)
    {
        Class<?> rt = am.getRawType();
        // Ok, first: must return an array type
        if (rt.isArray()) {
            /* And that type needs to be "net.sf.cglib.proxy.Callback".
             * Theoretically could just be a type that implements it, but
             * for now let's keep things simple, fix if need be.
             */
            Class<?> compType = rt.getComponentType();
            // Actually, let's just verify it's a "net.sf.cglib.*" class/interface
            String className = compType.getName();
            if (className.contains(".cglib")) {
                return className.startsWith("net.sf.cglib")
                    // also, as per [JACKSON-177]
                    || className.startsWith("org.hibernate.repackage.cglib")
                    // and [core#674]
                    || className.startsWith("org.springframework.cglib");
            }
        }
        return false;
    }

    /**
     * Another helper method to deal with Groovy's problematic metadata accessors
     */
    protected static boolean isGroovyMetaClassGetter(AnnotatedMember am) {
        return am.getRawType().getName().startsWith("groovy.lang");
    }
    /*
    /**********************************************************
    /* Actual name mangling methods
    /**********************************************************
     */

    // 24-Sep-2017, tatu: note that "std" here refers to earlier (1.x, 2.x) distinction
    //   between "legacy" (slightly non-conforming) and "std" (fully conforming): with 3.x
    //   only latter exists.
    public static String stdManglePropertyName(final String basename, final int offset)
    {
        final int end = basename.length();
        if (end == offset) { // empty name, nope
            return null;
        }
        // first: if it doesn't start with capital, return as-is
        char c0 = basename.charAt(offset);
        char c1 = Character.toLowerCase(c0);
        if (c0 == c1) {
            return basename.substring(offset);
        }
        // 17-Dec-2014, tatu: As per [databind#653], need to follow more
        //   closely Java Beans spec; specifically, if two first are upper-case,
        //   then no lower-casing should be done.
        if ((offset + 1) < end) {
            if (Character.isUpperCase(basename.charAt(offset+1))) {
                return basename.substring(offset);
            }
        }
        StringBuilder sb = new StringBuilder(end - offset);
        sb.append(c1);
        sb.append(basename, offset+1, end);
        return sb.toString();
    }
}
