// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_beans_Visibility__
#define __java_beans_Visibility__

#pragma interface

#include <java/lang/Object.h>

extern "Java"
{
  namespace java
  {
    namespace beans
    {
      class Visibility;
    }
  }
}

class java::beans::Visibility : public ::java::lang::Object
{
public:
  virtual jboolean needsGui () = 0;
  virtual jboolean avoidingGui () = 0;
  virtual void dontUseGui () = 0;
  virtual void okToUseGui () = 0;

  static ::java::lang::Class class$;
} __attribute__ ((java_interface));

#endif /* __java_beans_Visibility__ */
