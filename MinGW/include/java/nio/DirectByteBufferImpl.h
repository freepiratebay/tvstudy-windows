// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_nio_DirectByteBufferImpl__
#define __java_nio_DirectByteBufferImpl__

#pragma interface

#include <java/nio/ByteBuffer.h>

extern "Java"
{
  namespace java
  {
    namespace nio
    {
      class DirectByteBufferImpl;
      class ShortBuffer;
      class LongBuffer;
      class IntBuffer;
      class FloatBuffer;
      class DoubleBuffer;
      class CharBuffer;
      class ByteBuffer;
    }
  }
  namespace gnu
  {
    namespace gcj
    {
      class RawData;
    }
  }
}

class java::nio::DirectByteBufferImpl : public ::java::nio::ByteBuffer
{
public:
  DirectByteBufferImpl (::gnu::gcj::RawData *, jlong);
  DirectByteBufferImpl (::gnu::gcj::RawData *, jint, jint, jint, jint, jint, jboolean);
private:
  static ::gnu::gcj::RawData *allocateImpl (jint);
  static void freeImpl (::gnu::gcj::RawData *);
public:  // actually protected
  virtual void finalize ();
public:
  static ::java::nio::ByteBuffer *allocateDirect (jint);
private:
  jbyte getImpl (jint);
  void putImpl (jint, jbyte);
public:
  virtual jbyte get ();
  virtual jbyte get (jint);
  virtual ::java::nio::ByteBuffer *put (jbyte);
  virtual ::java::nio::ByteBuffer *put (jint, jbyte);
public: // actually package-private
  virtual void shiftDown (jint, jint, jint);
public:
  virtual ::java::nio::ByteBuffer *compact ();
  virtual ::java::nio::ByteBuffer *duplicate ();
  virtual ::java::nio::ByteBuffer *slice ();
  virtual ::java::nio::ByteBuffer *asReadOnlyBuffer ();
  virtual jboolean isReadOnly () { return readOnly; }
  virtual jboolean isDirect ();
  virtual ::java::nio::CharBuffer *asCharBuffer ();
  virtual ::java::nio::DoubleBuffer *asDoubleBuffer ();
  virtual ::java::nio::FloatBuffer *asFloatBuffer ();
  virtual ::java::nio::IntBuffer *asIntBuffer ();
  virtual ::java::nio::LongBuffer *asLongBuffer ();
  virtual ::java::nio::ShortBuffer *asShortBuffer ();
  jchar getChar ();
  ::java::nio::ByteBuffer *putChar (jchar);
  jchar getChar (jint);
  ::java::nio::ByteBuffer *putChar (jint, jchar);
  jshort getShort ();
  ::java::nio::ByteBuffer *putShort (jshort);
  jshort getShort (jint);
  ::java::nio::ByteBuffer *putShort (jint, jshort);
  jint getInt ();
  ::java::nio::ByteBuffer *putInt (jint);
  jint getInt (jint);
  ::java::nio::ByteBuffer *putInt (jint, jint);
  jlong getLong ();
  ::java::nio::ByteBuffer *putLong (jlong);
  jlong getLong (jint);
  ::java::nio::ByteBuffer *putLong (jint, jlong);
  jfloat getFloat ();
  ::java::nio::ByteBuffer *putFloat (jfloat);
  jfloat getFloat (jint);
  ::java::nio::ByteBuffer *putFloat (jint, jfloat);
  jdouble getDouble ();
  ::java::nio::ByteBuffer *putDouble (jdouble);
  jdouble getDouble (jint);
  ::java::nio::ByteBuffer *putDouble (jint, jdouble);
public: // actually package-private
  ::gnu::gcj::RawData * __attribute__((aligned(__alignof__( ::java::nio::ByteBuffer )))) address;
private:
  jint offset;
  jboolean readOnly;
public:

  static ::java::lang::Class class$;
};

#endif /* __java_nio_DirectByteBufferImpl__ */
