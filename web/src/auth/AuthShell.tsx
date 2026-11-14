import type { ReactNode } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/** The centered card that login, signup, onboarding and invites share. */
export function AuthShell({
  title,
  description,
  footer,
  children,
}: {
  title?: ReactNode;
  description?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-6 bg-muted/40 p-4">
      <p className="text-sm font-semibold">Spending Tracker</p>
      <Card className="w-full max-w-sm">
        {(title || description) && (
          <CardHeader>
            {title && (
              <CardTitle>
                <h1 className="text-xl font-medium">{title}</h1>
              </CardTitle>
            )}
            {description && <CardDescription>{description}</CardDescription>}
          </CardHeader>
        )}
        <CardContent className="flex flex-col gap-4">{children}</CardContent>
      </Card>
      {footer && <p className="text-sm text-muted-foreground [&_a]:text-foreground [&_a]:underline [&_a]:underline-offset-4">{footer}</p>}
    </main>
  );
}
