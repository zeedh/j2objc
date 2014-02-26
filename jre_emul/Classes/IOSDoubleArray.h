// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//
//  IOSDoubleArray.h
//  JreEmulation
//
//  Created by Tom Ball on 6/16/11.
//

#ifndef _IOSDoubleArray_H_
#define _IOSDoubleArray_H_

#import "IOSArray.h"

// An emulation class that represents a Java double array.  Like a Java array,
// an IOSDoubleArray is fixed-size but its elements are mutable.
@interface IOSDoubleArray : IOSArray {
 @public
  double *buffer_;  // java.nio requires this be first field in IOSArray subclasses.
}

PRIMITIVE_ARRAY_INTERFACE(double, Double, double)

@end

#endif // _IOSDoubleArray_H_
