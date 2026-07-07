# Add project specific ProGuard rules here.
# Deliberately empty: no -keepnames added for SampleResult/SampleInterface.
# That's the point of this test — typeString must survive R8 obfuscation
# on compile-time string literals alone, with zero per-class keep rules.
