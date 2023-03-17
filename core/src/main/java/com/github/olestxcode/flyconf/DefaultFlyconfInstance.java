package com.github.olestxcode.flyconf;

import com.github.olestxcode.flyconf.annotation.Configuration;
import com.github.olestxcode.flyconf.annotation.Mandatory;
import com.github.olestxcode.flyconf.annotation.MultiValue;
import com.github.olestxcode.flyconf.annotation.Property;
import com.github.olestxcode.flyconf.exception.InvalidConfigurationException;
import com.github.olestxcode.flyconf.loader.PropertyMapLoader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

class DefaultFlyconfInstance implements FlyconfInstance {

    private static final String ROOT_PATH = "";
    private static final String PATH_FORMAT = "%s.%s";
    private static final String RELOAD = "reload";

    private final Map<Class<?>, PropertyMapLoader> loaders = new HashMap<>();
    private final Map<Class<?>, Function<String, ?>> valueParserMap = new HashMap<>();
    {
        valueParserMap.put(String.class, Function.identity());
        valueParserMap.put(Integer.class, Integer::valueOf);
        valueParserMap.put(Integer.TYPE, Integer::parseInt);
        valueParserMap.put(Long.class, Long::valueOf);
        valueParserMap.put(Long.TYPE, Long::parseLong);
        valueParserMap.put(Short.class, Short::valueOf);
        valueParserMap.put(Short.TYPE, Short::parseShort);
        valueParserMap.put(Byte.class, Byte::valueOf);
        valueParserMap.put(Byte.TYPE, Byte::parseByte);
        valueParserMap.put(Float.class, Float::valueOf);
        valueParserMap.put(Float.TYPE, Float::parseFloat);
        valueParserMap.put(Double.class, Double::valueOf);
        valueParserMap.put(Double.TYPE, Double::parseDouble);
        valueParserMap.put(Boolean.class, Boolean::valueOf);
        valueParserMap.put(Boolean.TYPE, Boolean::parseBoolean);
        valueParserMap.put(Character.class, this::getSoleCharacter);
        valueParserMap.put(Character.TYPE, this::getSoleCharacter);

        valueParserMap.put(BigInteger.class, BigInteger::new);
        valueParserMap.put(BigDecimal.class, BigDecimal::new);

        valueParserMap.put(Duration.class, Duration::parse);
        valueParserMap.put(Instant.class, Instant::parse);
        valueParserMap.put(LocalDate.class, LocalDate::parse);
        valueParserMap.put(LocalDateTime.class, LocalDateTime::parse);
        valueParserMap.put(LocalTime.class, LocalTime::parse);
        valueParserMap.put(MonthDay.class, MonthDay::parse);
        valueParserMap.put(OffsetDateTime.class, OffsetDateTime::parse);
        valueParserMap.put(OffsetTime.class, OffsetTime::parse);
        valueParserMap.put(Period.class, Period::parse);
        valueParserMap.put(Year.class, Year::parse);
        valueParserMap.put(YearMonth.class, YearMonth::parse);
        valueParserMap.put(ZonedDateTime.class, ZonedDateTime::parse);
        valueParserMap.put(ZoneId.class, ZoneId::of);
        valueParserMap.put(ZoneOffset.class, ZoneOffset::of);
    }

    @Override
    public <T> T load(PropertyMapLoader loader, Class<T> into) {
        loaders.put(into, loader);
        return conf(into, new FlyconfInvocationHandler(ROOT_PATH, loader.load()));
    }

    @Override
    public <T> void registerCustomParser(Class<T> type, Function<String, T> parserFunction) {
        valueParserMap.put(type, parserFunction);
    }

    private char getSoleCharacter(String s) {
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Missing value");
        }
        if (s.length() > 1) {
            throw new IllegalArgumentException("More than one character");
        }
        return s.charAt(0);
    }

    private String formatProperty(String path, String property) {
        return ROOT_PATH.equals(path) ? property : PATH_FORMAT.formatted(path, property);
    }

    private <T> T conf(Class<T> config, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                config.getClassLoader(),
                new java.lang.Class[] { config },
                handler);
    }

    private class FlyconfInvocationHandler implements InvocationHandler {

        private final String path;
        private final Map<String, Object> propertyMap;

        FlyconfInvocationHandler(String path, Map<String, Object> propertyMap) {
            this.path = path;
            this.propertyMap = propertyMap;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getParameterCount() > 0) {
                throw new InvalidConfigurationException("Configuration methods cannot contain parameters!");
            }

            var methodName = method.getName();
            var methodType = method.getReturnType();

            Property customProperty = method.getAnnotation(Property.class);
            var property = customProperty != null ? customProperty.value() : methodName;

            if (methodType.isAnnotationPresent(Configuration.class)) {
                if (methodName.equals(RELOAD)) {
                    propertyMap.clear();
                    propertyMap.putAll(loaders.get(proxy.getClass()).load());
                }

                return conf(methodType, new FlyconfInvocationHandler(
                        formatProperty(path, property),
                        propertyMap
                ));
            }

            Object value = propertyMap.get(formatProperty(path, property));

            if (methodType.equals(Optional.class)) {
                if (value == null) {
                    return Optional.empty();
                }

                if (methodType.isAssignableFrom(value.getClass())) {
                    return Optional.of(value);
                }

                return Optional.of(valueParserMap.get(methodType).apply(value.toString()));
            }

            if (value == null && method.getAnnotation(Mandatory.class) != null) {
                throw new InvalidConfigurationException(
                        String.format("Mandatory property %s is not found!", property)
                );
            } else if (value == null) {
                return null;
            }

            if (methodType.isAssignableFrom(value.getClass())) {
                return value;
            }

            MultiValue multiValue = method.getAnnotation(MultiValue.class);
            if (multiValue != null) {
                if (Boolean.FALSE.equals(Iterable.class.isAssignableFrom(methodType))) {
                    throw new InvalidConfigurationException("@MultiValue annotation requires iterable type!");
                }

                return Arrays.stream(value.toString().split(multiValue.delimiter()))
                        .map(val -> valueParserMap.get((Class<?>) ((ParameterizedType) (method.getGenericReturnType()))
                                .getActualTypeArguments()[0]).apply(val))
                        .toList();
            }

            return valueParserMap.get(methodType).apply(value.toString());
        }
    }
}
