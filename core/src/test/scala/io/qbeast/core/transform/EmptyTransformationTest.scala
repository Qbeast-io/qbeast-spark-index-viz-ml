package io.qbeast.core.transform

import io.qbeast.core.model.DoubleDataType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EmptyTransformationTest extends AnyFlatSpec with Matchers {
  it should "always map to the same value" in {
    val t = EmptyTransformation()

    (1 to 100).foreach { i =>
      t.transform(i) shouldBe 0d
    }

    t.transform(null) shouldBe 0d
  }

  it should "be superseded by another Transformation" in {
    val et = EmptyTransformation()
    val ht = HashTransformation()
    val lt = LinearTransformation(1d, 1.1, DoubleDataType)

    et.isSupersededBy(ht) shouldBe true
    et.isSupersededBy(lt) shouldBe true
  }

  it should "return the other Transformation when merging" in {
    val et = EmptyTransformation()
    val ht = HashTransformation()
    val lt = LinearTransformation(1d, 1.1, DoubleDataType)

    et.merge(ht) shouldBe ht
    et.merge(lt) shouldBe lt
  }
}
