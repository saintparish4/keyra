package integration;

import org.scalatest.TagAnnotation;
import java.lang.annotation.*;

/** Tag for tests that require Docker (LocalStack). Exclude with: sbt unitTest */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Integration {}
