// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_sql_Savepoint__
#define __java_sql_Savepoint__

#pragma interface

#include <java/lang/Object.h>

extern "Java"
{
  namespace java
  {
    namespace sql
    {
      class Savepoint;
    }
  }
}

class java::sql::Savepoint : public ::java::lang::Object
{
public:
  virtual jint getSavepointId () = 0;
  virtual ::java::lang::String *getSavepointName () = 0;

  static ::java::lang::Class class$;
} __attribute__ ((java_interface));

#endif /* __java_sql_Savepoint__ */
