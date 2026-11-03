package com.smartelder.module.api;
import java.lang.annotation.*;

/** Every external HTTP handler declares the operation that the host must authorize. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface ModuleOperation { String resource(); String operation(); }
