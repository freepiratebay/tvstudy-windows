// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __javax_swing_event_MenuListener__
#define __javax_swing_event_MenuListener__

#pragma interface

#include <java/lang/Object.h>

extern "Java"
{
  namespace javax
  {
    namespace swing
    {
      namespace event
      {
        class MenuListener;
        class MenuEvent;
      }
    }
  }
}

class javax::swing::event::MenuListener : public ::java::lang::Object
{
public:
  virtual void menuCanceled (::javax::swing::event::MenuEvent *) = 0;
  virtual void menuDeselected (::javax::swing::event::MenuEvent *) = 0;
  virtual void menuSelected (::javax::swing::event::MenuEvent *) = 0;

  static ::java::lang::Class class$;
} __attribute__ ((java_interface));

#endif /* __javax_swing_event_MenuListener__ */
