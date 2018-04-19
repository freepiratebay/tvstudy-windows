// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_util_Arrays__
#define __java_util_Arrays__

#pragma interface

#include <java/lang/Object.h>
#include <gcj/array.h>

class java::util::Arrays : public ::java::lang::Object
{
private:
  Arrays ();
public:
  static jint binarySearch (jbyteArray, jbyte);
  static jint binarySearch (jcharArray, jchar);
  static jint binarySearch (jshortArray, jshort);
  static jint binarySearch (jintArray, jint);
  static jint binarySearch (jlongArray, jlong);
  static jint binarySearch (jfloatArray, jfloat);
  static jint binarySearch (jdoubleArray, jdouble);
  static jint binarySearch (JArray< ::java::lang::Object *> *, ::java::lang::Object *);
  static jint binarySearch (JArray< ::java::lang::Object *> *, ::java::lang::Object *, ::java::util::Comparator *);
  static jboolean equals (jbooleanArray, jbooleanArray);
  static jboolean equals (jbyteArray, jbyteArray);
  static jboolean equals (jcharArray, jcharArray);
  static jboolean equals (jshortArray, jshortArray);
  static jboolean equals (jintArray, jintArray);
  static jboolean equals (jlongArray, jlongArray);
  static jboolean equals (jfloatArray, jfloatArray);
  static jboolean equals (jdoubleArray, jdoubleArray);
  static jboolean equals (JArray< ::java::lang::Object *> *, JArray< ::java::lang::Object *> *);
  static void fill (jbooleanArray, jboolean);
  static void fill (jbooleanArray, jint, jint, jboolean);
  static void fill (jbyteArray, jbyte);
  static void fill (jbyteArray, jint, jint, jbyte);
  static void fill (jcharArray, jchar);
  static void fill (jcharArray, jint, jint, jchar);
  static void fill (jshortArray, jshort);
  static void fill (jshortArray, jint, jint, jshort);
  static void fill (jintArray, jint);
  static void fill (jintArray, jint, jint, jint);
  static void fill (jlongArray, jlong);
  static void fill (jlongArray, jint, jint, jlong);
  static void fill (jfloatArray, jfloat);
  static void fill (jfloatArray, jint, jint, jfloat);
  static void fill (jdoubleArray, jdouble);
  static void fill (jdoubleArray, jint, jint, jdouble);
  static void fill (JArray< ::java::lang::Object *> *, ::java::lang::Object *);
  static void fill (JArray< ::java::lang::Object *> *, jint, jint, ::java::lang::Object *);
  static void sort (jbyteArray);
  static void sort (jbyteArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jbyteArray);
  static void swap (jint, jint, jbyteArray);
  static void vecswap (jint, jint, jint, jbyteArray);
  static void qsort (jbyteArray, jint, jint);
public:
  static void sort (jcharArray);
  static void sort (jcharArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jcharArray);
  static void swap (jint, jint, jcharArray);
  static void vecswap (jint, jint, jint, jcharArray);
  static void qsort (jcharArray, jint, jint);
public:
  static void sort (jshortArray);
  static void sort (jshortArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jshortArray);
  static void swap (jint, jint, jshortArray);
  static void vecswap (jint, jint, jint, jshortArray);
  static void qsort (jshortArray, jint, jint);
public:
  static void sort (jintArray);
  static void sort (jintArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jintArray);
  static void swap (jint, jint, jintArray);
  static void vecswap (jint, jint, jint, jintArray);
  static jint compare (jint, jint);
  static void qsort (jintArray, jint, jint);
public:
  static void sort (jlongArray);
  static void sort (jlongArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jlongArray);
  static void swap (jint, jint, jlongArray);
  static void vecswap (jint, jint, jint, jlongArray);
  static jint compare (jlong, jlong);
  static void qsort (jlongArray, jint, jint);
public:
  static void sort (jfloatArray);
  static void sort (jfloatArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jfloatArray);
  static void swap (jint, jint, jfloatArray);
  static void vecswap (jint, jint, jint, jfloatArray);
  static void qsort (jfloatArray, jint, jint);
public:
  static void sort (jdoubleArray);
  static void sort (jdoubleArray, jint, jint);
private:
  static jint med3 (jint, jint, jint, jdoubleArray);
  static void swap (jint, jint, jdoubleArray);
  static void vecswap (jint, jint, jint, jdoubleArray);
  static void qsort (jdoubleArray, jint, jint);
public:
  static void sort (JArray< ::java::lang::Object *> *);
  static void sort (JArray< ::java::lang::Object *> *, ::java::util::Comparator *);
  static void sort (JArray< ::java::lang::Object *> *, jint, jint);
  static void sort (JArray< ::java::lang::Object *> *, jint, jint, ::java::util::Comparator *);
  static ::java::util::List *asList (JArray< ::java::lang::Object *> *);

  friend class java_util_Arrays$ArrayList;

  static ::java::lang::Class class$;
};

#endif /* __java_util_Arrays__ */
