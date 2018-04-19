// DO NOT EDIT THIS FILE - it is machine generated -*- c++ -*-

#ifndef __java_awt_image_DirectColorModel__
#define __java_awt_image_DirectColorModel__

#pragma interface

#include <java/awt/image/PackedColorModel.h>
#include <gcj/array.h>

extern "Java"
{
  namespace java
  {
    namespace awt
    {
      namespace image
      {
        class DirectColorModel;
        class Raster;
        class ColorModel;
        class WritableRaster;
      }
      namespace color
      {
        class ColorSpace;
      }
    }
  }
}

class java::awt::image::DirectColorModel : public ::java::awt::image::PackedColorModel
{
public:
  DirectColorModel (jint, jint, jint, jint);
  DirectColorModel (jint, jint, jint, jint, jint);
  DirectColorModel (::java::awt::color::ColorSpace *, jint, jint, jint, jint, jint, jboolean, jint);
  jint getRedMask ();
  jint getGreenMask ();
  jint getBlueMask ();
  jint getAlphaMask ();
  jint getRed (jint);
  jint getGreen (jint);
  jint getBlue (jint);
  jint getAlpha (jint);
private:
  jint extractAndNormalizeSample (jint, jint);
  jint extractAndScaleSample (jint, jint);
public:
  jint getRGB (jint);
  virtual jint getRed (::java::lang::Object *);
  virtual jint getGreen (::java::lang::Object *);
  virtual jint getBlue (::java::lang::Object *);
  virtual jint getAlpha (::java::lang::Object *);
  virtual jint getRGB (::java::lang::Object *);
  virtual ::java::lang::Object *getDataElements (jint, ::java::lang::Object *);
private:
  jint valueToField (jint, jint, jint);
  jint value16ToField (jint, jint);
public:
  jintArray getComponents (jint, jintArray, jint);
  jintArray getComponents (::java::lang::Object *, jintArray, jint);
  ::java::awt::image::WritableRaster *createCompatibleWritableRaster (jint, jint);
  virtual jint getDataElement (jintArray, jint);
  virtual ::java::lang::Object *getDataElements (jintArray, jint, ::java::lang::Object *);
  ::java::awt::image::ColorModel *coerceData (::java::awt::image::WritableRaster *, jboolean);
  virtual jboolean isCompatibleRaster (::java::awt::image::Raster *);
public: // actually package-private
  virtual ::java::lang::String *stringParam ();
public:
  virtual ::java::lang::String *toString ();

  static ::java::lang::Class class$;
};

#endif /* __java_awt_image_DirectColorModel__ */