// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_nio_IntBufferImpl__
#define __java_nio_IntBufferImpl__

#pragma interface

#include <java/nio/IntBuffer.h>
#include <gcj/array.h>

extern "Java"
{
  namespace java
  {
    namespace nio
    {
      class IntBufferImpl;
      class ByteOrder;
      class IntBuffer;
    }
  }
}

class java::nio::IntBufferImpl : public ::java::nio::IntBuffer
{
public: // actually package-private
  IntBufferImpl (jint);
  IntBufferImpl (jintArray, jint, jint, jint, jint, jint, jboolean);
public:
  jboolean isReadOnly () { return readOnly; }
  ::java::nio::IntBuffer *slice ();
  ::java::nio::IntBuffer *duplicate ();
  ::java::nio::IntBuffer *asReadOnlyBuffer ();
  ::java::nio::IntBuffer *compact ();
  jboolean isDirect ();
  jint get ();
  ::java::nio::IntBuffer *put (jint);
  jint get (jint);
  ::java::nio::IntBuffer *put (jint, jint);
  ::java::nio::ByteOrder *order ();
private:
  jboolean __attribute__((aligned(__alignof__( ::java::nio::IntBuffer ))))  readOnly;
public:

  static ::java::lang::Class class$;
};

#endif /* __java_nio_IntBufferImpl__ */