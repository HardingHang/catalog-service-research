package io.polaris.gateway.ref;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RefResolver {
  private static final String DEFAULT_REF = "main";

  public String resolve(String headerRef) {
    return (headerRef != null && !headerRef.isBlank()) ? headerRef : DEFAULT_REF;
  }
}
