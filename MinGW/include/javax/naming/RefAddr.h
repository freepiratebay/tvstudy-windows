// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __javax_naming_RefAddr__
#define __javax_naming_RefAddr__

#pragma interface

#include <java/lang/Object.h>

extern "Java"
{
  namespace javax
  {
    namespace naming
    {
      class RefAddr;
    }
  }
}

class javax::naming::RefAddr : public ::java::lang::Object
{
public:  // actually protected
  RefAddr (::java::lang::String *);
public:
  virtual ::java::lang::String *getType () { return addrType; }
  virtual ::java::lang::Object *getContent () = 0;
  virtual jboolean equals (::java::lang::Object *);
  virtual jint hashCode ();
  virtual ::java::lang::String *toString ();
public:  // actually protected
  ::java::lang::String * __attribute__((aligned(__alignof__( ::java::lang::Object )))) addrType;
public:

  static ::java::lang::Class class$;
};

#endif /* __javax_naming_RefAddr__ */
