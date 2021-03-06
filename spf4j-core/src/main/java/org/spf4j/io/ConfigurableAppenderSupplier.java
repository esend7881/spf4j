 /*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.spf4j.io;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.CheckReturnValue;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spf4j.base.Pair;

/**
 *
 * @author zoly
 */
@Beta
@ParametersAreNonnullByDefault
public final class ConfigurableAppenderSupplier implements ObjectAppenderSupplier {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigurableAppenderSupplier.class);

  private final ConcurrentMap<Class<?>, ObjectAppender<?>> lookup;

  private final LinkedList<Pair<Class<?>, ObjectAppender<?>>> registry;

  public static final Predicate<Class<?>> NO_FILTER = new Predicate<Class<?>>() {
    @Override
    public boolean apply(final Class<?> input) {
      return false;
    }
  };

  public ConfigurableAppenderSupplier() {
    this(true, NO_FILTER);
  }

  @SuppressWarnings("unchecked")
  public ConfigurableAppenderSupplier(final boolean registerFromServiceLoader, final Predicate<Class<?>> except,
          final ObjectAppender<?>... appenders) {
    lookup = new ConcurrentHashMap<>();
    registry = new LinkedList<>();
    if (registerFromServiceLoader) {
      @SuppressWarnings("unchecked")
      ServiceLoader<ObjectAppender<?>> load = (ServiceLoader) ServiceLoader.load(ObjectAppender.class);
      Iterator<ObjectAppender<?>> iterator = load.iterator();
      while (iterator.hasNext()) {
        ObjectAppender<?> appender = iterator.next();
        Class<?> appenderType = getAppenderType(appender);
        if (!except.apply(appenderType)) {
          if (!register((Class) appenderType, (ObjectAppender) appender)) {
            LOG.warn("Attempting to register duplicate appender({}) for {} ", appender, appenderType);
          }
        }
      }
    }
    for (ObjectAppender<?> appender : appenders) {
      if (!register(getAppenderType(appender), (ObjectAppender) appender)) {
        throw new IllegalArgumentException("Cannot register appender " + appender);
      }
    }
  }

  public static Class<?> getAppenderType(final ObjectAppender<?> appender) {
    Type[] genericInterfaces = appender.getClass().getGenericInterfaces();
    Class<?> appenderType = null;
    for (Type type : genericInterfaces) {
      if (type instanceof ParameterizedType) {
        ParameterizedType pType = (ParameterizedType) type;
        if (pType.getRawType() == ObjectAppender.class) {
          appenderType = (Class) pType.getActualTypeArguments()[0];
          break;
        }
      }
    }
    if (appenderType == null) {
      throw new IllegalArgumentException("Improperly declared Appender " + appender);
    }
    return appenderType;
  }

  @SuppressWarnings("unchecked")
  public <T> int register(final Class<T> type, final ObjectAppender<? super T>... appenders) {
    synchronized (registry) {
      int i = 0;
      for (ObjectAppender<? super T> appender : appenders) {
        if (!register(type, appender)) {
          break;
        } else {
          i++;
        }
      }
      lookup.clear();
      return i;
    }
  }

  public <T> void replace(final Class<T> type,
          final Function<ObjectAppender<? super T>, ObjectAppender<? super T>> replace) {
    synchronized (registry) {
      ListIterator<Pair<Class<?>, ObjectAppender<?>>> listIterator = registry.listIterator();
      while (listIterator.hasNext()) {
        Pair<Class<?>, ObjectAppender<?>> next = listIterator.next();
        final Class<?> nType = next.getFirst();
        if (nType == type) {
          listIterator.set((Pair) Pair.of(type, replace.apply((ObjectAppender) next.getSecond())));
          lookup.clear();
          return;
        }
      }
      registry.add((Pair) Pair.of(type, replace.apply((ObjectAppender) ObjectAppender.TOSTRING_APPENDER)));
      lookup.clear();
    }
  }

  @SuppressWarnings("unchecked")
  @CheckReturnValue
  private <T> boolean register(final Class<T> type, final ObjectAppender<? super T> appender) {
    synchronized (registry) {
      ListIterator<Pair<Class<?>, ObjectAppender<?>>> listIterator = registry.listIterator();
      while (listIterator.hasNext()) {
        Pair<Class<?>, ObjectAppender<?>> next = listIterator.next();
        final Class<?> nType = next.getFirst();
        if (nType.isAssignableFrom(type)) {
          if (nType == type) {
            return false;
          }
          listIterator.previous();
          listIterator.add((Pair) Pair.of(type, appender));
          return true;
        }
      }
      listIterator.add((Pair) Pair.of(type, appender));
    }
    return true;
  }

  public boolean unregister(final Class<?> type) {
    synchronized (registry) {
      ListIterator<Pair<Class<?>, ObjectAppender<?>>> listIterator = registry.listIterator();
      while (listIterator.hasNext()) {
        Pair<Class<?>, ObjectAppender<?>> item = listIterator.next();
          if (item.getFirst() == type) {
            listIterator.remove();
            lookup.clear();
            return true;
          }
      }
      return false;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ObjectAppender<? super T> get(final Class<T> type) {
    ObjectAppender<?> appender = lookup.get(type);
    if (appender != null) {
      return (ObjectAppender) appender;
    } else {
      synchronized (registry) {
        for (Map.Entry<Class<?>, ObjectAppender<?>> entry : registry) {
          Class<?> clasz = entry.getKey();
          if (clasz.isAssignableFrom(type)) {
            ObjectAppender<?> value = entry.getValue();
            lookup.put(type, value);
            return (ObjectAppender) value;
          }
        }
      }
      lookup.put(type, ObjectAppender.TOSTRING_APPENDER);
      return ObjectAppender.TOSTRING_APPENDER;
    }
  }

  @Override
  public String toString() {
    return "ConfigurableAppenderSupplier{" + "lookup=" + lookup + ", registry=" + registry + '}';
  }

}