// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __gnu_java_io_Base64InputStream__
#define __gnu_java_io_Base64InputStream__

#pragma interface

#include <java/io/FilterInputStream.h>
#include <gcj/array.h>

extern "Java"
{
  namespace gnu
  {
    namespace java
    {
      namespace io
      {
        class Base64InputStream;
      }
    }
  }
}

class gnu::java::io::Base64InputStream : public ::java::io::FilterInputStream
{
public:
  Base64InputStream (::java::io::InputStream *);
  virtual jint available ();
  virtual jint read ();
  virtual jint read (jbyteArray, jint, jint);
  virtual jboolean markSupported ();
  virtual void mark (jint) { }
  virtual void reset ();
  virtual jlong skip (jlong);
private:
  static ::java::lang::String *BASE_64;
  static const jint BASE_64_PAD = 61L;
  jint __attribute__((aligned(__alignof__( ::java::io::FilterInputStream ))))  state;
  jint temp;
  jboolean eof;
  jbyteArray one;
public:

  static ::java::lang::Class class$;
};

#endif /* __gnu_java_io_Base64InputStream__ */