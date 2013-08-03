package us.codecraft.webmagic.oo;

import org.apache.commons.lang3.StringUtils;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.selector.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author code4crafter@gmail.com <br>
 * @date: 13-8-1 <br>
 * Time: 下午9:33 <br>
 */
class PageModelExtractor {

    private List<Pattern> targetUrlPatterns = new ArrayList<Pattern>();

    private Selector targetUrlRegionSelector;

    private List<Pattern> helpUrlPatterns = new ArrayList<Pattern>();

    private Selector helpUrlRegionSelector;

    private Class clazz;

    private List<FieldExtractor> fieldExtractors;

    public static PageModelExtractor create(Class clazz) {
        PageModelExtractor pageModelExtractor = new PageModelExtractor();
        pageModelExtractor.init(clazz);
        return pageModelExtractor;
    }

    private void init(Class clazz) {
        this.clazz = clazz;
        initTargetUrlPatterns();
        fieldExtractors = new ArrayList<FieldExtractor>();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            ExtractBy extractBy = field.getAnnotation(ExtractBy.class);
            if (extractBy != null) {
                if (!extractBy.multi() && !String.class.isAssignableFrom(field.getType())) {
                    throw new IllegalStateException("Field " + field.getName() + " must be string");
                } else if (extractBy.multi() && !List.class.isAssignableFrom(field.getType())) {
                    throw new IllegalStateException("Field " + field.getName() + " must be list");
                }
                String value = extractBy.value();
                Selector selector;
                switch (extractBy.type()) {
                    case Css:
                        selector = new CssSelector(value);
                        break;
                    case Regex:
                        selector = new RegexSelector(value);
                        break;
                    case XPath:
                        selector = new XpathSelector(value);
                        break;
                    case XPath2:
                        selector = new Xpath2Selector(value);
                        break;
                    default:
                        selector = new Xpath2Selector(value);
                }
                FieldExtractor fieldExtractor = new FieldExtractor(field, selector, FieldExtractor.Source.Html, extractBy.notNull(), extractBy.multi());
                Method setterMethod = getSetterMethod(clazz, field);
                if (setterMethod != null) {
                    fieldExtractor.setSetterMethod(setterMethod);
                }
                fieldExtractors.add(fieldExtractor);
            }
            ExtractByUrl extractByUrl = field.getAnnotation(ExtractByUrl.class);
            if (extractByUrl != null) {
                if (!extractByUrl.multi() && !String.class.isAssignableFrom(field.getType())) {
                    throw new IllegalStateException("Field " + field.getName() + " must be string");
                } else if (extractByUrl.multi() && !List.class.isAssignableFrom(field.getType())) {
                    throw new IllegalStateException("Field " + field.getName() + " must be list");
                }
                String regexPattern = extractByUrl.value();
                if (regexPattern.trim().equals("")) {
                    regexPattern = ".*";
                }
                FieldExtractor fieldExtractor = new FieldExtractor(field, new RegexSelector(regexPattern), FieldExtractor.Source.Url, extractByUrl.notNull(), extractByUrl.multi());
                Method setterMethod = getSetterMethod(clazz, field);
                if (setterMethod != null) {
                    fieldExtractor.setSetterMethod(setterMethod);
                }
                fieldExtractors.add(fieldExtractor);
            }
        }
    }

    public static Method getSetterMethod(Class clazz, Field field) {
        String name = "set" + StringUtils.capitalize(field.getName());
        try {
            Method declaredMethod = clazz.getDeclaredMethod(name, field.getType());
            declaredMethod.setAccessible(true);
            return declaredMethod;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private void initTargetUrlPatterns() {
        Annotation annotation = clazz.getAnnotation(TargetUrl.class);
        if (annotation == null) {
            targetUrlPatterns.add(Pattern.compile(".*"));
        } else {
            TargetUrl targetUrl = (TargetUrl) annotation;
            String[] value = targetUrl.value();
            for (String s : value) {
                targetUrlPatterns.add(Pattern.compile("("+s.replace(".", "\\.").replace("*", "[^\"'#]*")+")"));
            }
            if (!targetUrl.sourceRegion().equals("")){
                targetUrlRegionSelector = new Xpath2Selector(targetUrl.sourceRegion());
            }
        }
        annotation = clazz.getAnnotation(HelpUrl.class);
        if (annotation != null) {
            HelpUrl helpUrl = (HelpUrl) annotation;
            String[] value = helpUrl.value();
            for (String s : value) {
                helpUrlPatterns.add(Pattern.compile("("+s.replace(".", "\\.").replace("*", "[^\"'#]*")+")"));
            }
            if (!helpUrl.sourceRegion().equals("")){
                helpUrlRegionSelector = new Xpath2Selector(helpUrl.sourceRegion());
            }
        }
    }

    public Object process(Page page) {
        boolean matched = false;
        for (Pattern targetPattern : targetUrlPatterns) {
            if (targetPattern.matcher(page.getUrl().toString()).matches()) {
                matched = true;
            }
        }
        if (!matched) {
            return null;
        }
        Object o = null;
        try {
            o = clazz.newInstance();
            for (FieldExtractor fieldExtractor : fieldExtractors) {
                if (fieldExtractor.multi) {
                    List<String> value;
                    switch (fieldExtractor.getSource()) {
                        case Html:
                            value = fieldExtractor.getSelector().selectList(page.getHtml().toString());
                            break;
                        case Url:
                            value = fieldExtractor.getSelector().selectList(page.getUrl().toString());
                            break;
                        default:
                            value = fieldExtractor.getSelector().selectList(page.getHtml().toString());
                    }
                    if ((value == null || value.size() == 0) && fieldExtractor.isNotNull()) {
                        page.getResultItems().setSkip(true);
                    }
                    setField(o, fieldExtractor, value);
                } else {
                    String value;
                    switch (fieldExtractor.getSource()) {
                        case Html:
                            value = fieldExtractor.getSelector().select(page.getHtml().toString());
                            break;
                        case Url:
                            value = fieldExtractor.getSelector().select(page.getUrl().toString());
                            break;
                        default:
                            value = fieldExtractor.getSelector().select(page.getHtml().toString());
                    }
                    if (value == null && fieldExtractor.isNotNull()) {
                        page.getResultItems().setSkip(true);
                    }
                    setField(o, fieldExtractor, value);
                }
            }
            if (AfterExtractor.class.isAssignableFrom(clazz)) {
                ((AfterExtractor)o).afterProcess(page);
            }
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return o;
    }

    private void setField(Object o, FieldExtractor fieldExtractor, Object value) throws IllegalAccessException, InvocationTargetException {
        if (fieldExtractor.getSetterMethod() != null) {
            fieldExtractor.getSetterMethod().invoke(o, value);
        }
        fieldExtractor.getField().set(o, value);
    }

    Class getClazz() {
        return clazz;
    }

    List<Pattern> getTargetUrlPatterns() {
        return targetUrlPatterns;
    }

    List<Pattern> getHelpUrlPatterns() {
        return helpUrlPatterns;
    }

    Selector getTargetUrlRegionSelector() {
        return targetUrlRegionSelector;
    }

    Selector getHelpUrlRegionSelector() {
        return helpUrlRegionSelector;
    }
}