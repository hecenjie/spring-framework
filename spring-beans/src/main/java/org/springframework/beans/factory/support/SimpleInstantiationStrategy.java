/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Simple object instantiation strategy for use in a BeanFactory.
 *
 * <p>Does not support Method Injection, although it provides hooks for subclasses
 * to override to add Method Injection support, for example by overriding methods.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 */
public class SimpleInstantiationStrategy implements InstantiationStrategy {

	private static final ThreadLocal<Method> currentlyInvokedFactoryMethod = new ThreadLocal<>();


	/**
	 * Return the factory method currently being invoked or {@code null} if none.
	 * <p>Allows factory method implementations to determine whether the current
	 * caller is the container itself as opposed to user code.
	 */
	@Nullable
	public static Method getCurrentlyInvokedFactoryMethod() {
		return currentlyInvokedFactoryMethod.get();
	}


	@Override
	public Object instantiate(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner) {
		// Don't override the class with CGLIB if no overrides.
		// 没有覆盖方法，直接使用反射实例化即可
		if (!bd.hasMethodOverrides()) {
			Constructor<?> constructorToUse;
			synchronized (bd.constructorArgumentLock) {
				// 从缓存中获得构造方法 constructorToUse
				constructorToUse = (Constructor<?>) bd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse == null) {		// 如果缓存中不存在
					final Class<?> clazz = bd.getBeanClass();	// 获得 Bean 的 class 对象
					if (clazz.isInterface()) {	// 如果是接口，则抛出 BeanInstantiationException 异常
						throw new BeanInstantiationException(clazz, "Specified class is an interface");
					}
					try {
						if (System.getSecurityManager() != null) {
							constructorToUse = AccessController.doPrivileged(
									(PrivilegedExceptionAction<Constructor<?>>) clazz::getDeclaredConstructor);
						} else {
							constructorToUse = clazz.getDeclaredConstructor();		// 从 class 对象中获得构造函数，赋给 constructorToUse
						}
						bd.resolvedConstructorOrFactoryMethod = constructorToUse;	// 将该构造函数加入到缓存中
					} catch (Throwable ex) {
						throw new BeanInstantiationException(clazz, "No default constructor found", ex);
					}
				}
			}
			// 通过 BeanUtils 直接使用构造器对象实例化 Bean 对象
			// 核心代码其实就是 constructorToUse.newInstance();
			return BeanUtils.instantiateClass(constructorToUse);
		} else {
			// Must generate CGLIB subclass.
			// 生成 CGLIB 创建的子类对象
			return instantiateWithMethodInjection(bd, beanName, owner);
		}
	}

	/**
	 * Subclasses can override this method, which is implemented to throw
	 * UnsupportedOperationException, if they can instantiate an object with
	 * the Method Injection specified in the given RootBeanDefinition.
	 * Instantiation should use a no-arg constructor.
	 */
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner) {
		throw new UnsupportedOperationException("Method Injection not supported in SimpleInstantiationStrategy");
	}

	@Override
	public Object instantiate(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner,
			final Constructor<?> ctor, @Nullable Object... args) {
		// 没有覆盖方法，直接使用反射实例化即可
		if (!bd.hasMethodOverrides()) {
			if (System.getSecurityManager() != null) {
				// use own privileged to change accessibility (when security is on)
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					ReflectionUtils.makeAccessible(ctor);
					return null;
				});
			}
			// 通过 BeanUtils 直接使用构造器对象实例化 Bean 对象
			// 核心代码其实就是 constructorToUse.newInstance();
			return (args != null ? BeanUtils.instantiateClass(ctor, args) : BeanUtils.instantiateClass(ctor));
		} else {
			// 生成 CGLIB 创建的子类对象
			return instantiateWithMethodInjection(bd, beanName, owner, ctor, args);
		}
	}

	/**
	 * Subclasses can override this method, which is implemented to throw
	 * UnsupportedOperationException, if they can instantiate an object with
	 * the Method Injection specified in the given RootBeanDefinition.
	 * Instantiation should use the given constructor and parameters.
	 */
	protected Object instantiateWithMethodInjection(RootBeanDefinition bd, @Nullable String beanName,
			BeanFactory owner, @Nullable Constructor<?> ctor, @Nullable Object... args) {
		// 将CGLIB的实例化策略委托其实现
		throw new UnsupportedOperationException("Method Injection not supported in SimpleInstantiationStrategy");
	}

	@Override
	public Object instantiate(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner,
			@Nullable Object factoryBean, final Method factoryMethod, @Nullable Object... args) {

		try {
			if (System.getSecurityManager() != null) {
				AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
					ReflectionUtils.makeAccessible(factoryMethod);
					return null;
				});
			} else {
				ReflectionUtils.makeAccessible(factoryMethod);	// 设置method可访问
			}

			// 获得原 Method 对象
			// currentlyInvokedFactoryMethod 用于判断当前线程是否正在创建某个 bean
			Method priorInvokedFactoryMethod = currentlyInvokedFactoryMethod.get();
			try {
				currentlyInvokedFactoryMethod.set(factoryMethod);			// 设置新的 Method 对象到 currentlyInvokedFactoryMethod 中
				Object result = factoryMethod.invoke(factoryBean, args);	// 创建 Bean 对象
				if (result == null) {	// 创建失败，则创建 NullBean 对象
					result = new NullBean();
				}
				return result;
			} finally {
				// 恢复现场
				if (priorInvokedFactoryMethod != null) {
					currentlyInvokedFactoryMethod.set(priorInvokedFactoryMethod);
				} else {
					currentlyInvokedFactoryMethod.remove();
				}
			}
			// 一大堆 catch 异常
		} catch (IllegalArgumentException ex) {
			throw new BeanInstantiationException(factoryMethod,
					"Illegal arguments to factory method '" + factoryMethod.getName() + "'; " +
					"args: " + StringUtils.arrayToCommaDelimitedString(args), ex);
		} catch (IllegalAccessException ex) {
			throw new BeanInstantiationException(factoryMethod,
					"Cannot access factory method '" + factoryMethod.getName() + "'; is it public?", ex);
		} catch (InvocationTargetException ex) {
			String msg = "Factory method '" + factoryMethod.getName() + "' threw exception";
			if (bd.getFactoryBeanName() != null && owner instanceof ConfigurableBeanFactory &&
					((ConfigurableBeanFactory) owner).isCurrentlyInCreation(bd.getFactoryBeanName())) {
				msg = "Circular reference involving containing bean '" + bd.getFactoryBeanName() + "' - consider " +
						"declaring the factory method as static for independence from its containing instance. " + msg;
			}
			throw new BeanInstantiationException(factoryMethod, msg, ex.getTargetException());
		}
	}

}
