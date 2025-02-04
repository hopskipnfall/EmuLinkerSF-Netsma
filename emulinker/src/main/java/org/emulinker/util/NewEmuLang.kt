package org.emulinker.util

import com.google.common.flogger.FluentLogger
import java.lang.Exception
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

object NewEmuLang {
  private const val BUNDLE_NAME = "messages"

  private val RESOURCE_BUNDLE = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH)

  private val logger = FluentLogger.forEnclosingClass()

  /*
  	public static void reload()
  	{
  		try
  		{
  			Class klass = RESOURCE_BUNDLE.getClass().getSuperclass();
  			Field field = klass.getDeclaredField("cacheList");
  			field.setAccessible(true);
  			sun.misc.SoftCache cache = (sun.misc.SoftCache)field.get(null);
  			cache.clear();
  		}
  		catch(Exception e)
  		{

  		}
  	}
  */

  fun hasString(key: String): Boolean {
    if (RESOURCE_BUNDLE.containsKey(key)) {
      try {
        RESOURCE_BUNDLE.getString(key)
        return true
      } catch (e: Exception) {
        // It exists but is not readable.
        e.printStackTrace()
      }
    }
    return false
  }

  fun getString(key: String): String = RESOURCE_BUNDLE.getString(key)

  fun getString(key: String, vararg messageArgs: Any): String {
    val str = RESOURCE_BUNDLE.getString(key)
    return MessageFormat(str).format(messageArgs)
  }
}
