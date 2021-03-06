/*
 * Hermes - GWT Server-side I18N Library
 * Copyright (C) 2011  Matt Bertolini
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package com.mattbertolini.hermes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import com.google.gwt.i18n.client.LocalizableResource.Key;
import com.google.gwt.i18n.client.Messages.DefaultMessage;
import com.google.gwt.i18n.client.Messages.PluralCount;
import com.google.gwt.i18n.client.PluralRule;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;

/**
 * 
 * @author Matt Bertolini
 */
public class MessagesProxy extends AbstractI18nProxy {
    private PluralRules pluralRules;
    
    public MessagesProxy(Class<?> clazz, String lang, ULocale locale, Properties properties) {
        super(clazz, lang, locale, properties);
        this.pluralRules = PluralRules.forLocale(this.getLocale());
    }
    
    @Override
    public Object parse(Method method, Object[] args) {
        return this.parseMessage(method, args);
    }

    private Object parseMessage(Method method, Object[] args) {
        String messageName;
        Key keyAnnotation = method.getAnnotation(Key.class);
        if(keyAnnotation == null) {
            messageName = method.getName();
        } else {
            messageName = keyAnnotation.value();
        }
        
        boolean hasPluralArgument = false;
        int pluralArgumentIndex = -1;
        boolean hasCustomPluralRules = false;
        PluralRule customRule = null;
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for(int i = 0; i < parameterTypes.length; i++) {
            Annotation[] annotations = parameterAnnotations[i];
            Class<?> type = parameterTypes[i];
            for(Annotation annotation : annotations) {
                if(PluralCount.class.isAssignableFrom(annotation.annotationType()) 
                        && (int.class.isAssignableFrom(type) 
                                || Integer.class.isAssignableFrom(type)
                                || short.class.isAssignableFrom(type)
                                || Short.class.isAssignableFrom(type))) {
                    hasPluralArgument = true;
                    pluralArgumentIndex = i;
                    // Check for a custom plural rule.
                    PluralCount pc = (PluralCount) annotation;
                    Class<? extends PluralRule> pluralRuleClass = pc.value();
                    if(!pluralRuleClass.isInterface() && PluralRule.class.isAssignableFrom(pluralRuleClass)) {
                        customRule = this.instantiateCustomPluralRuleClass(pluralRuleClass);
                        hasCustomPluralRules = true;
                    }
                    break;
                }
            }
            if(hasPluralArgument) {
                break;
            }
        }
        
        String pattern;
        if(hasPluralArgument) {
            Number num = (Number) args[pluralArgumentIndex];
            String patternName = null;
            Plural plural = null;
            if(hasCustomPluralRules) {
                plural = CustomPlural.fromNumber(customRule, num.intValue());
            } else {
                plural = GwtPlural.fromNumber(this.pluralRules, num.doubleValue());
            }
            patternName = plural.buildPatternName(messageName);
            pattern = this.getProperties().getProperty(patternName);
            if(pattern == null) {
                Map<Plural, String> defaultValues = plural.buildDefaultPluralValueMap(method);
                pattern = defaultValues.get(plural);
            }
        } else {
            pattern = this.getProperties().getProperty(messageName);
            if(pattern == null) {
                // check for default message annotation
                DefaultMessage defaultMessage = method.getAnnotation(DefaultMessage.class);
                if(defaultMessage != null) {
                    pattern = defaultMessage.value();
                }
            }
        }
         
        MessageFormat formatter = new MessageFormat(pattern, this.getLocale());
        Object retVal;
        if(method.getReturnType().equals(SafeHtml.class)) {
            Object[] safeArgs = null;
            if(args != null) {
                safeArgs = new Object[args.length];
                for(int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    Class<?> argType = parameterTypes[i];
                    if(SafeHtml.class.isAssignableFrom(argType)) {
                        SafeHtml sh = (SafeHtml) arg;
                        safeArgs[i] = sh.asString();
                    } else if(Number.class.isAssignableFrom(argType) 
                            || Date.class.isAssignableFrom(argType)) {
                        // Because of the subformat pattern of dates and 
                        // numbers, we cannot escape them.
                        safeArgs[i] = arg;
                    } else {
                        safeArgs[i] = SafeHtmlUtils.htmlEscape(arg.toString());
                    }
                }
            }
            String formattedString = formatter.format(safeArgs, new StringBuffer(), null).toString();
            // Would rather use fromSafeConstant() but doesn't work on server.
            retVal = SafeHtmlUtils.fromTrustedString(formattedString);
        } else {
            retVal = formatter.format(args, new StringBuffer(), null).toString();
        }
        
        return retVal;
    }
    
    /**
     * Instantiates the plural rule class given inside the PluralCount 
     * annotation. The pluralRule class that is instantiated is based on the 
     * language originally given to the library. If that language is not found, 
     * defaults to the given class.
     * 
     * @param clazz The PluralRule class
     * @return An instantiated PluralRule class
     */
    private PluralRule instantiateCustomPluralRuleClass(Class<? extends PluralRule> clazz) {
        PluralRule retVal = null;
        try {
            String pluralClassName = clazz.getName() + "_" + this.getLocale().getLanguage();
            try {
                Class<?> pClass = Class.forName(pluralClassName);
                retVal = (PluralRule) pClass.newInstance();
            } catch (ClassNotFoundException e) {
                retVal = clazz.newInstance();
            }
        } catch (InstantiationException e) {
            throw new RuntimeException("Could not instantiate custom PluralRule class. " 
                    + "Make sure the class is not an abstract class or interface and has a default constructor.", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not instantiate custom Plural Rule class.", e);
        }
        return retVal;
    }
}
