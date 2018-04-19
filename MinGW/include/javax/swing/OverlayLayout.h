// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __javax_swing_OverlayLayout__
#define __javax_swing_OverlayLayout__

#pragma interface

#include <java/lang/Object.h>
#include <gcj/array.h>

extern "Java"
{
  namespace javax
  {
    namespace swing
    {
      class OverlayLayout;
      class SizeRequirements;
    }
  }
  namespace java
  {
    namespace awt
    {
      class Dimension;
      class Component;
      class Container;
    }
  }
}

class javax::swing::OverlayLayout : public ::java::lang::Object
{
public:
  OverlayLayout (::java::awt::Container *);
  virtual void invalidateLayout (::java::awt::Container *) { }
  virtual void addLayoutComponent (::java::lang::String *, ::java::awt::Component *) { }
  virtual void addLayoutComponent (::java::awt::Component *, ::java::lang::Object *) { }
  virtual void removeLayoutComponent (::java::awt::Component *) { }
  virtual ::java::awt::Dimension *preferredLayoutSize (::java::awt::Container *) { return 0; }
  virtual ::java::awt::Dimension *minimumLayoutSize (::java::awt::Container *) { return 0; }
  virtual ::java::awt::Dimension *maximumLayoutSize (::java::awt::Container *) { return 0; }
  virtual jfloat getLayoutAlignmentX (::java::awt::Container *);
  virtual jfloat getLayoutAlignmentY (::java::awt::Container *);
  virtual void layoutContainer (::java::awt::Container *) { }
public: // actually package-private
  static const jlong serialVersionUID = 18082829169631543LL;
private:
  ::java::awt::Container * __attribute__((aligned(__alignof__( ::java::lang::Object )))) target;
  JArray< ::javax::swing::SizeRequirements *> *xChildren;
  JArray< ::javax::swing::SizeRequirements *> *yChildren;
  ::javax::swing::SizeRequirements *xTotal;
  ::javax::swing::SizeRequirements *yTotal;
public:

  static ::java::lang::Class class$;
};

#endif /* __javax_swing_OverlayLayout__ */
