public final class Runner {
    public static void main(String[] args) throws Exception {
        Class<?> fixture = Class.forName("xyz.daikirai.Fixture");
        fixture.getMethod("run").invoke(null);
        System.out.println("REMAP-OK");
    }
}
